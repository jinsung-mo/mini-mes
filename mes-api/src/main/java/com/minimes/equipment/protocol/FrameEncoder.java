package com.minimes.equipment.protocol;

import static com.minimes.equipment.protocol.FrameFormat.ENVELOPE_LENGTH;
import static com.minimes.equipment.protocol.FrameFormat.ETX;
import static com.minimes.equipment.protocol.FrameFormat.LENGTH_DIGITS;
import static com.minimes.equipment.protocol.FrameFormat.MAX_BODY_LENGTH;
import static com.minimes.equipment.protocol.FrameFormat.STX;

import java.nio.charset.StandardCharsets;

/**
 * {@link Frame}을 보낼 바이트로 바꾼다. 형식은 {@link FrameFormat}.
 */
public final class FrameEncoder {

	private FrameEncoder() {
	}

	public static byte[] encode(Frame frame) {
		String body = frame.body();
		for (int i = 0; i < body.length(); i++) {
			if (Character.isISOControl(body.charAt(i))) {
				throw new IllegalArgumentException("body must not contain control characters");
			}
		}
		byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
		if (bodyBytes.length > MAX_BODY_LENGTH) {
			throw new IllegalArgumentException("body is longer than " + MAX_BODY_LENGTH + " bytes");
		}

		String header = padRight(frame.type(), Frame.TYPE_LENGTH)
				+ padRight(frame.messageId(), Frame.MESSAGE_ID_LENGTH)
				+ String.format("%0" + LENGTH_DIGITS + "d", bodyBytes.length);
		byte[] headerBytes = header.getBytes(StandardCharsets.US_ASCII);

		byte[] bytes = new byte[ENVELOPE_LENGTH + bodyBytes.length];
		bytes[0] = STX;
		System.arraycopy(headerBytes, 0, bytes, 1, headerBytes.length);
		System.arraycopy(bodyBytes, 0, bytes, 1 + headerBytes.length, bodyBytes.length);
		bytes[bytes.length - 1] = ETX;
		return bytes;
	}

	private static String padRight(String value, int length) {
		return value + " ".repeat(length - value.length());
	}

}
