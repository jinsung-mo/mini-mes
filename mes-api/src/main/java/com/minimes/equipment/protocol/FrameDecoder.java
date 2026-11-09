package com.minimes.equipment.protocol;

import static com.minimes.equipment.protocol.FrameFormat.ENVELOPE_LENGTH;
import static com.minimes.equipment.protocol.FrameFormat.ETX;
import static com.minimes.equipment.protocol.FrameFormat.HEADER_LENGTH;
import static com.minimes.equipment.protocol.FrameFormat.LENGTH_DIGITS;
import static com.minimes.equipment.protocol.FrameFormat.STX;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.minimes.equipment.protocol.DecodeResult.Decoded;
import com.minimes.equipment.protocol.DecodeResult.Malformed;

/**
 * TCP로 받은 바이트에서 전문을 꺼낸다. 형식은 {@link FrameFormat}.
 * <p>
 * TCP는 메시지 경계가 없는 바이트 흐름이다. 설비가 전문 두 개를 따로 보내도 한 번에 읽힐 수 있고(뭉침),
 * 전문 하나가 여러 번에 나눠 읽힐 수도 있다(쪼개짐). 그래서 받은 바이트를 버퍼에 쌓아 두고,
 * STX부터 ETX까지 완전한 전문이 모였을 때만 꺼낸다.
 * <p>
 * 형식이 틀린 바이트를 만나면 다음 STX까지 버리고 거기서 다시 읽는다(재동기화).
 * 깨진 전문 하나 때문에 연결 전체를 끊지 않기 위해서다.
 * <p>
 * 연결 하나마다 디코더 하나를 쓴다. 상태(버퍼)가 있어서 스레드 사이에 공유하면 안 된다.
 */
public final class FrameDecoder {

	private byte[] buffer = new byte[256];
	private int size;

	/**
	 * 받은 바이트를 넣고, 이번에 완성된 전문들을 받은 순서대로 돌려준다. 아직 덜 온 전문은 버퍼에 남겨 둔다.
	 */
	public List<DecodeResult> feed(byte[] data, int offset, int length) {
		append(data, offset, length);
		List<DecodeResult> results = new ArrayList<>();
		DecodeResult result;
		while ((result = next()) != null) {
			results.add(result);
		}
		return results;
	}

	public List<DecodeResult> feed(byte[] data) {
		return feed(data, 0, data.length);
	}

	/** 아직 전문이 되지 못하고 버퍼에 남은 바이트 수. 연결이 끊길 때 0이 아니면 마지막 전문이 잘린 것이다 */
	public int pendingBytes() {
		return size;
	}

	/** 버퍼 앞에서 결과 하나를 꺼낸다. 더 받아야 하면 null */
	private DecodeResult next() {
		if (size == 0) {
			return null;
		}
		if (buffer[0] != STX) {
			int stx = indexOfStx(1, size);
			int discarded = stx < 0 ? size : stx;
			consume(discarded);
			return new Malformed(null, "bytes before STX", discarded);
		}

		// 본문에는 STX가 올 수 없으므로, 프레임이 끝나기 전에 STX가 보이면 이 프레임은 깨졌다.
		// 길이만 믿고 기다리면, 잘못된 길이(예: 9999) 때문에 뒤에 온 정상 전문까지 붙잡고 있게 된다.
		int frameLength = declaredFrameLength();
		int scanEnd = frameLength < 0 ? size : Math.min(size, frameLength - 1);
		if (indexOfStx(1, scanEnd) >= 0) {
			return dropFrame(readMessageId(), "STX inside the frame (previous frame was cut off)");
		}

		if (size < 1 + HEADER_LENGTH) {
			return null;
		}
		String messageId = readMessageId();
		String type = readType();
		if (type == null) {
			return dropFrame(messageId, "invalid type");
		}
		if (messageId == null) {
			return dropFrame(null, "invalid message id");
		}
		if (frameLength < 0) {
			return dropFrame(messageId, "invalid body length");
		}
		if (size < frameLength) {
			return null;
		}
		if (buffer[frameLength - 1] != ETX) {
			return dropFrame(messageId, "ETX not found where the body length says the frame ends");
		}

		String body;
		try {
			body = StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(buffer, 1 + HEADER_LENGTH, frameLength - ENVELOPE_LENGTH))
					.toString();
		}
		catch (CharacterCodingException e) {
			consume(frameLength);
			return new Malformed(messageId, "body is not valid UTF-8", frameLength);
		}
		consume(frameLength);
		return new Decoded(new Frame(type, messageId, body));
	}

	/** 헤더의 본문 길이로 계산한 프레임 전체 길이. 길이 칸을 아직 다 받지 못했거나 숫자가 아니면 -1 */
	private int declaredFrameLength() {
		int start = 1 + Frame.TYPE_LENGTH + Frame.MESSAGE_ID_LENGTH;
		if (size < start + LENGTH_DIGITS) {
			return -1;
		}
		int bodyLength = 0;
		for (int i = start; i < start + LENGTH_DIGITS; i++) {
			byte b = buffer[i];
			if (b < '0' || b > '9') {
				return -1;
			}
			bodyLength = bodyLength * 10 + (b - '0');
		}
		return ENVELOPE_LENGTH + bodyLength;
	}

	private String readType() {
		String type = readAscii(1, Frame.TYPE_LENGTH);
		if (type == null) {
			return null;
		}
		type = type.stripTrailing();
		return Frame.TYPE.matcher(type).matches() ? type : null;
	}

	/** 메시지 ID 칸을 다 받았고 형식이 맞으면 그 값, 아니면 null */
	private String readMessageId() {
		int start = 1 + Frame.TYPE_LENGTH;
		if (size < start + Frame.MESSAGE_ID_LENGTH) {
			return null;
		}
		String id = readAscii(start, Frame.MESSAGE_ID_LENGTH);
		if (id == null) {
			return null;
		}
		id = id.stripTrailing();
		return Frame.MESSAGE_ID.matcher(id).matches() ? id : null;
	}

	/** 출력 가능한 ASCII(0x20~0x7E)가 아니면 null */
	private String readAscii(int start, int length) {
		for (int i = start; i < start + length; i++) {
			if (buffer[i] < 0x20 || buffer[i] > 0x7E) {
				return null;
			}
		}
		return new String(buffer, start, length, StandardCharsets.US_ASCII);
	}

	/** 맨 앞 STX부터 다음 STX 직전까지 버린다. 다음 STX가 없으면 받은 것을 모두 버린다 */
	private Malformed dropFrame(String messageId, String reason) {
		int stx = indexOfStx(1, size);
		int discarded = stx < 0 ? size : stx;
		consume(discarded);
		return new Malformed(messageId, reason, discarded);
	}

	private int indexOfStx(int from, int to) {
		for (int i = from; i < to; i++) {
			if (buffer[i] == STX) {
				return i;
			}
		}
		return -1;
	}

	private void append(byte[] data, int offset, int length) {
		if (size + length > buffer.length) {
			buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, size + length));
		}
		System.arraycopy(data, offset, buffer, size, length);
		size += length;
	}

	private void consume(int count) {
		System.arraycopy(buffer, count, buffer, 0, size - count);
		size -= count;
	}

}
