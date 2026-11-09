package com.minimes.equipment.protocol;

/**
 * 전문의 바이트 형식. 규격 설명은 docs/04-equipment-interface.md에 있다.
 *
 * <pre>
 * STX | 전문 종류 4 | 메시지 ID 20 | 본문 길이 4 | 본문 0~9999 | ETX
 * 0x02  "RSLT"       "EQ-BODY-01-000001   "  "0042"   UTF-8         0x03
 * </pre>
 * <ul>
 * <li>헤더(전문 종류, 메시지 ID, 본문 길이)는 ASCII 고정 길이다. 문자열은 뒤를 공백으로, 숫자는 앞을 0으로 채운다</li>
 * <li>본문 길이는 글자 수가 아니라 UTF-8 <b>바이트 수</b>다</li>
 * <li>본문에는 제어 문자(STX, ETX 포함)를 넣을 수 없다. 그래서 프레임 중간에 STX가 보이면 앞 프레임이 깨진 것이다</li>
 * </ul>
 */
final class FrameFormat {

	static final byte STX = 0x02;
	static final byte ETX = 0x03;

	static final int LENGTH_DIGITS = 4;
	static final int MAX_BODY_LENGTH = 9999;

	/** STX 뒤에 오는 헤더 길이: 전문 종류 4 + 메시지 ID 20 + 본문 길이 4 */
	static final int HEADER_LENGTH = Frame.TYPE_LENGTH + Frame.MESSAGE_ID_LENGTH + LENGTH_DIGITS;

	/** 본문을 뺀 프레임 길이: STX + 헤더 + ETX */
	static final int ENVELOPE_LENGTH = 1 + HEADER_LENGTH + 1;

	private FrameFormat() {
	}

}
