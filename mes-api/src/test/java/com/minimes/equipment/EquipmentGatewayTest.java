package com.minimes.equipment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.minimes.equipment.protocol.Frame;
import com.minimes.equipment.protocol.FrameEncoder;

/**
 * 실제 소켓으로 게이트웨이를 확인한다. DB 없이, 받은 본문을 그대로 ACK로 돌려주는 가짜 처리기를 쓴다.
 */
class EquipmentGatewayTest {

	private static final Duration READ_TIMEOUT = Duration.ofMillis(500);

	private EquipmentGateway gateway;

	@AfterEach
	void stop() {
		if (gateway != null && gateway.isRunning()) {
			gateway.stop();
		}
	}

	private int start(FrameHandler handler, int maxConnections) {
		gateway = new EquipmentGateway(new EquipmentProperties(0, "127.0.0.1", READ_TIMEOUT, maxConnections), handler);
		gateway.start();
		return gateway.getPort();
	}

	private int startEcho() {
		return start(request -> new Frame(Frame.ACK, request.messageId(), request.body()), 2);
	}

	@Test
	void 전문을_받으면_같은_메시지_ID로_응답한다() throws Exception {
		try (EquipmentClient client = new EquipmentClient(startEcho())) {
			assertEquals(new Frame(Frame.ACK, "M1", "a=1"), client.request(new Frame(Frame.RESULT, "M1", "a=1")));
		}
	}

	@Test
	void 한_바이트씩_쪼개_보내도_전문_하나로_처리한다() throws Exception {
		try (EquipmentClient client = new EquipmentClient(startEcho())) {
			for (byte b : FrameEncoder.encode(new Frame(Frame.RESULT, "M1", "woId=15|goodQty=1"))) {
				client.sendRaw(new byte[] { b });
			}
			assertEquals(new Frame(Frame.ACK, "M1", "woId=15|goodQty=1"), client.receive());
		}
	}

	@Test
	void 한_번에_뭉쳐_보내면_받은_순서대로_하나씩_응답한다() throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		for (String id : List.of("M1", "M2", "M3")) {
			bytes.writeBytes(FrameEncoder.encode(new Frame(Frame.RESULT, id, "")));
		}
		try (EquipmentClient client = new EquipmentClient(startEcho())) {
			client.sendRaw(bytes.toByteArray());
			assertEquals("M1", client.receive().messageId());
			assertEquals("M2", client.receive().messageId());
			assertEquals("M3", client.receive().messageId());
		}
	}

	@Test
	void 깨진_전문은_NAK로_알리고_연결은_계속_쓴다() throws Exception {
		byte[] broken = FrameEncoder.encode(new Frame(Frame.RESULT, "BAD1", "a=1"));
		broken[broken.length - 1] = 'x'; // ETX 자리를 깨뜨림
		try (EquipmentClient client = new EquipmentClient(startEcho())) {
			client.sendRaw(broken);
			client.sendRaw("noise".getBytes(StandardCharsets.US_ASCII)); // 메시지 ID를 모르니 응답 없음
			client.send(new Frame(Frame.RESULT, "OK1", ""));

			Frame nak = client.receive();
			assertEquals(Frame.NAK, nak.type());
			assertEquals("BAD1", nak.messageId());
			assertTrue(nak.body().startsWith("code=E001|"), nak.body());
			assertEquals(new Frame(Frame.ACK, "OK1", ""), client.receive());
		}
	}

	@Test
	void 읽기_타임아웃_동안_아무것도_안_오면_서버가_연결을_닫는다() throws Exception {
		try (EquipmentClient client = new EquipmentClient(startEcho())) {
			assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertTrue(client.closedByServer()));
		}
	}

	@Test
	void HBT를_보내면_타임아웃보다_오래_연결이_유지된다() throws Exception {
		try (EquipmentClient client = new EquipmentClient(startEcho())) {
			for (int i = 0; i < 4; i++) { // 300ms x 4 = 1.2초 > 타임아웃 0.5초
				Thread.sleep(300);
				assertEquals(Frame.ACK, client.request(new Frame(Frame.HEARTBEAT, "H" + i, "")).type());
			}
		}
	}

	@Test
	void 최대_연결_수를_넘으면_새_연결을_닫는다() throws Exception {
		// 처리기를 붙잡아 두어 연결 두 개가 스레드를 차지한 상태를 만든다
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch busy = new CountDownLatch(2);
		int port = start(request -> {
			busy.countDown();
			try {
				release.await(5, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return new Frame(Frame.ACK, request.messageId(), "");
		}, 2);

		try (EquipmentClient first = new EquipmentClient(port);
				EquipmentClient second = new EquipmentClient(port)) {
			first.send(new Frame(Frame.RESULT, "A", ""));
			second.send(new Frame(Frame.RESULT, "B", ""));
			assertTrue(busy.await(3, TimeUnit.SECONDS));

			try (EquipmentClient third = new EquipmentClient(port)) {
				assertTrue(third.closedByServer());
			}
			release.countDown();
			assertEquals("A", first.receive().messageId());
			assertEquals("B", second.receive().messageId());
		}
	}

	@Test
	void 게이트웨이를_멈추면_열린_연결도_닫는다() throws Exception {
		try (EquipmentClient client = new EquipmentClient(startEcho())) {
			assertEquals(Frame.ACK, client.request(new Frame(Frame.HEARTBEAT, "H1", "")).type());
			gateway.stop();
			assertTrue(client.closedByServer());
		}
	}

}
