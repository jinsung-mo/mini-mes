"""가짜 PLC(설비). 설비 TCP 게이트웨이에 실적 전문을 보낸다.

실제 설비가 하는 일을 흉내 낸다. 표준 라이브러리만 쓴다.
  - 실적(RSLT)을 보내고 ACK/NAK를 기다린다. 정해진 시간 안에 답이 없으면 같은 메시지 ID로 다시 보낸다
  - 연결이 끊기거나 접속이 거부되면 기다렸다가 다시 접속한다. 기다리는 시간은 0.5초에서 두 배씩 늘려 최대 4초
  - 보낼 실적이 없는 동안에는 10초마다 HBT를 보내 연결을 유지한다 (게이트웨이 읽기 타임아웃은 30초)

  python scripts/plc_simulator.py --wo 131 --eqp EQ-BODY-01 --count 10
  python scripts/plc_simulator.py --wo 131 --eqp EQ-BODY-01 --count 5 --fault lose-ack

장애 흉내 (--fault)
  split     전문 하나를 3바이트씩 쪼개서 20ms 간격으로 보낸다
  lose-ack  ACK를 받고도 못 받은 것처럼 같은 전문을 한 번 더 보낸다. 두 ACK가 같아야 한다
  cut       전문을 절반만 보내고 연결을 끊는다. 다시 접속해서 전문 전체를 다시 보낸다

전문 형식은 docs/04-equipment-interface.md와 같다.
  STX(0x02) | 종류 4 | 메시지 ID 20 | 본문 길이 4 (UTF-8 바이트 수) | 본문 | ETX(0x03)
"""
import argparse
import datetime
import socket
import sys
import time
from dataclasses import dataclass, field

STX, ETX = 0x02, 0x03
HEADER_LENGTH = 28          # 종류 4 + 메시지 ID 20 + 본문 길이 4


def encode(kind, message_id, body=""):
    data = body.encode("utf-8")
    header = kind.ljust(4) + message_id.ljust(20) + f"{len(data):04d}"
    return bytes([STX]) + header.encode("ascii") + data + bytes([ETX])


def parse_body(body):
    return dict(item.split("=", 1) for item in body.split("|")) if body else {}


@dataclass
class Reply:
    kind: str
    message_id: str
    body: str

    @property
    def fields(self):
        return parse_body(self.body)


class FrameReader:
    """받은 바이트를 모아 전문 단위로 자른다. 서버가 보낸 전문만 읽으므로 재동기화는 STX 찾기만 한다"""

    def __init__(self):
        self.buffer = bytearray()

    def feed(self, data):
        self.buffer += data
        replies = []
        while True:
            start = self.buffer.find(STX)
            if start < 0:
                self.buffer.clear()
                return replies
            del self.buffer[:start]
            if len(self.buffer) < 1 + HEADER_LENGTH:
                return replies
            header = self.buffer[1:1 + HEADER_LENGTH].decode("ascii")
            length = int(header[24:28])
            end = 1 + HEADER_LENGTH + length
            if len(self.buffer) < end + 1:
                return replies
            body = self.buffer[1 + HEADER_LENGTH:end].decode("utf-8")
            replies.append(Reply(header[:4].strip(), header[4:24].strip(), body))
            del self.buffer[:end + 1]


@dataclass
class Stats:
    sent: int = 0           # 보낸 실적 전문 수 (재전송 제외)
    acked: int = 0
    naked: int = 0
    resends: int = 0        # 같은 전문을 다시 보낸 횟수 (타임아웃, 끊김, lose-ack)
    reconnects: int = 0     # 처음 접속을 뺀 접속 횟수
    connect_ms: list = field(default_factory=list)
    nak_codes: list = field(default_factory=list)


class Plc:
    def __init__(self, eqp_cd, host="127.0.0.1", port=9101, ack_timeout=3.0, heartbeat=10.0,
                 max_backoff=4.0, max_attempts=60, log=print):
        self.eqp_cd = eqp_cd
        self.proc_cd = eqp_cd.split("-")[1]          # EQ-BODY-01 → BODY
        self.host, self.port = host, port
        self.ack_timeout, self.heartbeat = ack_timeout, heartbeat
        self.max_backoff, self.max_attempts = max_backoff, max_attempts
        self.log = log
        self.sock = None
        self.reader = FrameReader()
        self.last_sent = 0.0
        self.stats = Stats()
        self.run_id = format(int(time.time() * 1000) % 36 ** 6, "x")[-6:]
        self.seq = 0

    # ------------------------------------------------------------ 연결

    def connect(self):
        """접속될 때까지 다시 시도한다. 접속 거부(WAS가 내려가 있음)도 같은 방법으로 기다린다"""
        backoff = 0.5
        for attempt in range(self.max_attempts):
            started = time.perf_counter()
            try:
                self.sock = socket.create_connection((self.host, self.port), timeout=5)
            except OSError as e:
                self.log(f"  접속 실패({e.__class__.__name__}), {backoff:.1f}초 뒤 다시 시도")
                time.sleep(backoff)
                backoff = min(backoff * 2, self.max_backoff)
                continue
            self.stats.connect_ms.append((time.perf_counter() - started) * 1000)
            self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.reader = FrameReader()                # 끊긴 연결에서 받다 만 바이트는 버린다
            if self.stats.connect_ms[1:]:
                self.stats.reconnects += 1
            return
        raise ConnectionError(f"{self.host}:{self.port}에 {self.max_attempts}번 접속하지 못했습니다")

    def close(self):
        if self.sock:
            try:
                self.sock.close()
            except OSError:
                pass
            self.sock = None

    def __enter__(self):
        self.connect()
        return self

    def __exit__(self, *exc):
        self.close()

    # ------------------------------------------------------------ 보내고 받기

    def send(self, data, chunk=None, gap=0.0):
        if chunk is None:
            self.sock.sendall(data)
        else:
            for i in range(0, len(data), chunk):
                self.sock.sendall(data[i:i + chunk])
                time.sleep(gap)
        self.last_sent = time.monotonic()

    def wait_reply(self, message_id):
        """같은 메시지 ID의 답을 기다린다. 앞서 재전송한 전문의 늦은 답 같은 다른 ID는 버린다"""
        deadline = time.monotonic() + self.ack_timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError
            self.sock.settimeout(remaining)
            data = self.sock.recv(4096)
            if not data:
                raise ConnectionResetError("서버가 연결을 닫음")
            for reply in self.reader.feed(data):
                if reply.message_id == message_id:
                    return reply

    def request(self, data, message_id, chunk=None, gap=0.0):
        """보내고 답을 받는다. 타임아웃이면 같은 연결로, 끊겼으면 다시 접속해서 같은 전문을 다시 보낸다"""
        for attempt in range(self.max_attempts):
            if attempt:
                self.stats.resends += 1
            try:
                if self.sock is None:
                    self.connect()
                self.send(data, chunk, gap)
                return self.wait_reply(message_id)
            except TimeoutError:
                self.log(f"  {message_id}: {self.ack_timeout}초 안에 답이 없어 다시 보냄")
            except OSError as e:
                self.log(f"  {message_id}: 연결 끊김({e.__class__.__name__}), 다시 접속해서 다시 보냄")
                self.close()
        raise ConnectionError(f"{message_id}: {self.max_attempts}번 보냈지만 답을 받지 못했습니다")

    def ping(self):
        message_id = f"H{self.run_id}-{int(time.time())}"
        return self.request(encode("HBT", message_id), message_id)

    def idle(self, seconds):
        """보낼 실적이 없는 동안 기다린다. 마지막으로 보낸 지 heartbeat초가 지나면 HBT를 보낸다"""
        until = time.monotonic() + seconds
        while (now := time.monotonic()) < until:
            if now - self.last_sent >= self.heartbeat:
                self.ping()
            time.sleep(min(0.5, until - now))

    # ------------------------------------------------------------ 실적

    def next_message_id(self):
        self.seq += 1
        return f"{self.run_id}-{self.seq:06d}"

    def result_body(self, wo_id, good_qty, defect_qty=0, defect_cd=None):
        fields = {"woId": wo_id, "procCd": self.proc_cd, "eqpCd": self.eqp_cd,
                  "goodQty": good_qty, "defectQty": defect_qty}
        if defect_cd:
            fields["defectCd"] = defect_cd
        # 보고 시각은 처음 만들 때 정한다. 재전송하는 전문은 본문까지 처음과 같아야 한다
        fields["reportedAt"] = datetime.datetime.now().astimezone().isoformat(timespec="seconds")
        return "|".join(f"{k}={v}" for k, v in fields.items())

    def report(self, wo_id, good_qty, defect_qty=0, defect_cd=None, fault=None):
        message_id = self.next_message_id()
        data = encode("RSLT", message_id, self.result_body(wo_id, good_qty, defect_qty, defect_cd))
        self.stats.sent += 1

        if fault == "cut":
            if self.sock is None:
                self.connect()
            self.send(data[:len(data) // 2])
            self.close()                               # 절반만 보내고 끊는다. 다음 request가 다시 접속한다
            self.stats.resends += 1
        if fault == "split":
            reply = self.request(data, message_id, chunk=3, gap=0.02)
        else:
            reply = self.request(data, message_id)
        if fault == "lose-ack":
            self.stats.resends += 1
            again = self.request(data, message_id)
            if again != reply:
                raise AssertionError(f"{message_id}: 재전송 ACK가 처음과 다름\n  처음 {reply}\n  다시 {again}")

        if reply.kind == "ACK":
            self.stats.acked += 1
        else:
            self.stats.naked += 1
            self.stats.nak_codes.append(reply.fields.get("code"))
        return message_id, reply


def main():
    parser = argparse.ArgumentParser(description="가짜 PLC: 설비 게이트웨이에 실적 전문을 보낸다")
    parser.add_argument("--wo", type=int, required=True, help="작업지시 ID (진행 중이어야 함)")
    parser.add_argument("--eqp", default="EQ-BODY-01", help="설비 코드. 공정은 가운데 부분(EQ-BODY-01 → BODY)")
    parser.add_argument("--count", type=int, default=10, help="보낼 실적 수")
    parser.add_argument("--qty", type=int, default=1, help="실적 하나의 양품 수")
    parser.add_argument("--interval", type=float, default=1.0, help="실적 사이 간격(초)")
    parser.add_argument("--fault", choices=["split", "lose-ack", "cut"])
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9101)
    args = parser.parse_args()

    with Plc(args.eqp, args.host, args.port) as plc:
        print(f"{args.host}:{args.port} 접속 ({plc.stats.connect_ms[0]:.1f}ms), 설비 {args.eqp}")
        for i in range(args.count):
            message_id, reply = plc.report(args.wo, args.qty, fault=args.fault)
            fields = reply.fields
            if reply.kind == "ACK":
                print(f"{message_id} ACK  {fields['lot_no']}  {fields['shift_cd']}조  "
                      f"양품 누계 {fields['wo_good_qty']}  {fields['wo_status']}")
            else:
                print(f"{message_id} NAK  {fields.get('code')}  {fields.get('message')}")
            if i < args.count - 1:
                plc.idle(args.interval)
        s = plc.stats
        print(f"보냄 {s.sent}  ACK {s.acked}  NAK {s.naked}  재전송 {s.resends}  재접속 {s.reconnects}")
    return 0 if s.naked == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
