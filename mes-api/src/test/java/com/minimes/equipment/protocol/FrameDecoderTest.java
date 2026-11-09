package com.minimes.equipment.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.minimes.equipment.protocol.DecodeResult.Decoded;
import com.minimes.equipment.protocol.DecodeResult.Malformed;

class FrameDecoderTest {

	private static final Frame RESULT = new Frame(Frame.RESULT, "EQ-BODY-01-000001",
			"woId=15|procCd=BODY|eqpCd=EQ-BODY-01|goodQty=1|defectQty=0");
	private static final Frame HEARTBEAT = new Frame(Frame.HEARTBEAT, "EQ-BODY-01-000002", "");

	@Test
	void 인코딩한_바이트는_규격의_자리에_맞는다() {
		byte[] bytes = FrameEncoder.encode(new Frame("ACK", "M1", "a=1"));
		String expected = "\u0002" + "ACK " + "M1" + " ".repeat(18) + "0003" + "a=1" + "\u0003";
		assertArrayEquals(expected.getBytes(StandardCharsets.US_ASCII), bytes);
	}

	@Test
	void 전문_하나를_그대로_꺼낸다() {
		List<DecodeResult> results = new FrameDecoder().feed(FrameEncoder.encode(RESULT));
		assertEquals(List.of(new Decoded(RESULT)), results);
	}

	@Test
	void 빈_본문도_전문이다() {
		byte[] bytes = FrameEncoder.encode(HEARTBEAT);
		assertEquals(30, bytes.length, "STX + 헤더 28 + ETX");
		assertEquals(List.of(new Decoded(HEARTBEAT)), new FrameDecoder().feed(bytes));
	}

	@Test
	void 쪼개져_들어오면_다_모일_때까지_기다린다() {
		// 1바이트씩 받는 가장 나쁜 경우
		FrameDecoder decoder = new FrameDecoder();
		byte[] bytes = FrameEncoder.encode(RESULT);
		List<DecodeResult> results = new ArrayList<>();
		for (int i = 0; i < bytes.length; i++) {
			List<DecodeResult> partial = decoder.feed(bytes, i, 1);
			if (i < bytes.length - 1) {
				assertTrue(partial.isEmpty(), "ETX 전에 꺼내면 안 된다: " + i);
			}
			results.addAll(partial);
		}
		assertEquals(List.of(new Decoded(RESULT)), results);
		assertEquals(0, decoder.pendingBytes());
	}

	@Test
	void 뭉쳐_들어오면_하나씩_잘라낸다() {
		byte[] bytes = concat(FrameEncoder.encode(RESULT), FrameEncoder.encode(HEARTBEAT), FrameEncoder.encode(RESULT));
		List<DecodeResult> results = new FrameDecoder().feed(bytes);
		assertEquals(List.of(new Decoded(RESULT), new Decoded(HEARTBEAT), new Decoded(RESULT)), results);
	}

	@Test
	void 뭉침과_쪼개짐이_섞여도_순서대로_꺼낸다() {
		// 첫 번째 전문 전체 + 두 번째 전문 앞부분 / 두 번째 전문 나머지
		byte[] bytes = concat(FrameEncoder.encode(RESULT), FrameEncoder.encode(HEARTBEAT));
		int cut = FrameEncoder.encode(RESULT).length + 10;
		FrameDecoder decoder = new FrameDecoder();

		assertEquals(List.of(new Decoded(RESULT)), decoder.feed(bytes, 0, cut));
		assertEquals(10, decoder.pendingBytes());
		assertEquals(List.of(new Decoded(HEARTBEAT)), decoder.feed(bytes, cut, bytes.length - cut));
	}

	@Test
	void 본문_길이는_글자_수가_아니라_UTF8_바이트_수다() {
		Frame korean = new Frame(Frame.NAK, "M1", "message=작업지시 없음");
		byte[] bytes = FrameEncoder.encode(korean);
		// "message=" 8바이트 + 한글 6자 x 3바이트 + 공백 1바이트 = 27바이트 (글자 수는 15)
		assertEquals("0027",new String(bytes, 25, 4, StandardCharsets.US_ASCII));
		assertEquals(List.of(new Decoded(korean)), new FrameDecoder().feed(bytes));
	}

	@Test
	void STX_앞의_쓰레기_바이트는_버리고_다음_전문을_읽는다() {
		byte[] bytes = concat("noise".getBytes(StandardCharsets.US_ASCII), FrameEncoder.encode(RESULT));
		List<DecodeResult> results = new FrameDecoder().feed(bytes);

		assertEquals(2, results.size());
		assertEquals(new Malformed(null, "bytes before STX", 5), results.get(0));
		assertEquals(new Decoded(RESULT), results.get(1));
	}

	@Test
	void 길이가_틀려서_ETX_자리가_어긋나면_그_전문만_버린다() {
		byte[] broken = FrameEncoder.encode(RESULT);
		broken[25] = '0'; // 길이 0058 → 0050: 본문을 8바이트 짧게 말한다
		broken[26] = '0';
		broken[27] = '5';
		broken[28] = '0';
		List<DecodeResult> results = new FrameDecoder().feed(concat(broken, FrameEncoder.encode(HEARTBEAT)));

		Malformed malformed = assertInstanceOf(Malformed.class, results.get(0));
		assertEquals("EQ-BODY-01-000001", malformed.messageId(), "메시지 ID를 읽었으면 NAK로 알릴 수 있다");
		assertEquals(broken.length, malformed.discardedBytes());
		assertEquals(new Decoded(HEARTBEAT), results.get(1));
	}

	@Test
	void 중간에_잘린_전문_뒤에_새_전문이_오면_길이만큼_기다리지_않고_버린다() {
		// 설비가 보내다 멈추고 처음부터 다시 보낸 경우. 잘린 전문의 길이를 믿고 기다리면 새 전문까지 붙잡힌다
		byte[] whole = FrameEncoder.encode(RESULT);
		byte[] cutOff = Arrays.copyOf(whole, 40);
		List<DecodeResult> results = new FrameDecoder().feed(concat(cutOff, whole));

		Malformed malformed = assertInstanceOf(Malformed.class, results.get(0));
		assertEquals(40, malformed.discardedBytes());
		assertEquals(new Decoded(RESULT), results.get(1));
	}

	@Test
	void 헤더_형식이_틀리면_버린다() {
		assertMalformed(replace(FrameEncoder.encode(RESULT), 1, "rslt"), "invalid type");
		assertMalformed(replace(FrameEncoder.encode(RESULT), 5, "EQ BODY"), "invalid message id");
		assertMalformed(replace(FrameEncoder.encode(RESULT), 25, "00x8"), "invalid body length");
	}

	@Test
	void 본문이_UTF8이_아니면_버린다() {
		byte[] bytes = FrameEncoder.encode(new Frame(Frame.RESULT, "M1", "a=b"));
		bytes[31] = (byte) 0xFF;
		Malformed malformed = assertInstanceOf(Malformed.class, new FrameDecoder().feed(bytes).get(0));
		assertEquals("body is not valid UTF-8", malformed.reason());
		assertEquals("M1", malformed.messageId());
	}

	@Test
	void 연결이_끊길_때_남은_바이트로_잘린_전문을_알_수_있다() {
		FrameDecoder decoder = new FrameDecoder();
		decoder.feed(FrameEncoder.encode(RESULT), 0, 20);
		assertEquals(20, decoder.pendingBytes());
	}

	@Test
	void 보낼_수_없는_전문은_인코딩하지_않는다() {
		assertThrows(IllegalArgumentException.class, () -> new Frame("RESULT", "M1", ""));
		assertThrows(IllegalArgumentException.class, () -> new Frame(Frame.RESULT, "M 1", ""));
		assertThrows(IllegalArgumentException.class, () -> new Frame(Frame.RESULT, "M".repeat(21), ""));
		assertThrows(IllegalArgumentException.class,
				() -> FrameEncoder.encode(new Frame(Frame.RESULT, "M1", "a=\u0002")));
		assertThrows(IllegalArgumentException.class,
				() -> FrameEncoder.encode(new Frame(Frame.RESULT, "M1", "a".repeat(10_000))));
	}

	private static void assertMalformed(byte[] bytes, String reason) {
		List<DecodeResult> results = new FrameDecoder().feed(bytes);
		Malformed malformed = assertInstanceOf(Malformed.class, results.get(0));
		assertEquals(reason, malformed.reason());
		assertEquals(1, results.size());
	}

	private static byte[] replace(byte[] bytes, int at, String ascii) {
		byte[] replacement = ascii.getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(replacement, 0, bytes, at, replacement.length);
		return bytes;
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] part : parts) {
			out.writeBytes(part);
		}
		return out.toByteArray();
	}

}
