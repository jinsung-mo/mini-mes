package com.minimes.equipment.protocol;

/**
 * {@link FrameDecoder}가 받은 바이트에서 꺼낸 결과 하나. 정상 전문이거나, 버려야 하는 잘못된 바이트다.
 */
public sealed interface DecodeResult {

	/** 완전한 전문 하나 */
	record Decoded(Frame frame) implements DecodeResult {
	}

	/**
	 * 형식이 틀려서 버린 바이트.
	 *
	 * @param messageId 헤더에서 메시지 ID를 읽을 수 있었으면 그 값, 아니면 null.
	 * 값이 있으면 설비에 NAK로 알릴 수 있고, 없으면 로그만 남긴다
	 * @param reason 버린 이유
	 * @param discardedBytes 버린 바이트 수
	 */
	record Malformed(String messageId, String reason, int discardedBytes) implements DecodeResult {
	}

}
