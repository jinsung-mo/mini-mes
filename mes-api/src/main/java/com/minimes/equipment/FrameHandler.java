package com.minimes.equipment;

import com.minimes.equipment.protocol.Frame;

/**
 * 받은 전문 하나를 처리하고 응답 전문을 돌려준다. 응답하지 않으면 null.
 * {@link EquipmentGateway}는 소켓과 전문 경계만 다루고, 전문의 의미는 이 인터페이스에 맡긴다.
 * 그래서 게이트웨이는 DB 없이 가짜 처리기로 테스트할 수 있다.
 */
@FunctionalInterface
public interface FrameHandler {

	Frame handle(Frame request);

}
