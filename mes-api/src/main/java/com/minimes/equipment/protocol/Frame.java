package com.minimes.equipment.protocol;

import java.util.regex.Pattern;

/**
 * 설비와 주고받는 전문 하나. 바이트 형식은 {@link FrameDecoder}에 정리했다.
 *
 * @param type 전문 종류 (RSLT, HBT, ACK, NAK). 바이트로는 4자리이고 짧으면 뒤를 공백으로 채운다
 * @param messageId 설비가 붙이는 메시지 ID. 응답(ACK, NAK)은 요청과 같은 ID를 쓴다
 * @param body 본문 ({@code key=value|key=value}). 없으면 빈 문자열
 */
public record Frame(String type, String messageId, String body) {

	public static final String RESULT = "RSLT";
	public static final String HEARTBEAT = "HBT";
	public static final String ACK = "ACK";
	public static final String NAK = "NAK";

	public static final int TYPE_LENGTH = 4;
	public static final int MESSAGE_ID_LENGTH = 20;

	/** 메시지 ID에 쓸 수 있는 문자. 뒤를 공백으로 채우므로 공백은 쓸 수 없다 */
	static final Pattern MESSAGE_ID = Pattern.compile("[A-Za-z0-9._-]{1," + MESSAGE_ID_LENGTH + "}");
	static final Pattern TYPE = Pattern.compile("[A-Z]{1," + TYPE_LENGTH + "}");

	public Frame {
		if (type == null || !TYPE.matcher(type).matches()) {
			throw new IllegalArgumentException("type must be 1-4 upper-case letters: " + type);
		}
		if (messageId == null || !MESSAGE_ID.matcher(messageId).matches()) {
			throw new IllegalArgumentException("messageId must be 1-20 of [A-Za-z0-9._-]: " + messageId);
		}
		if (body == null) {
			body = "";
		}
	}

}
