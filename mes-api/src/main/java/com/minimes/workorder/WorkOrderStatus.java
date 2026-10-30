package com.minimes.workorder;

/**
 * 작업지시 상태. 레거시는 "IN_PROGRESS" 같은 문자열을 코드 곳곳에 직접 썼다(레거시 문제점 5).
 * enum으로 모으면 오타를 컴파일러가 잡고, 상태 전이 규칙도 한곳에서 관리할 수 있다.
 */
public enum WorkOrderStatus {

	CREATED, RELEASED, IN_PROGRESS, COMPLETED;

	/** CREATED → RELEASED → IN_PROGRESS → COMPLETED 순서로 한 단계씩만 바뀐다 */
	public boolean canChangeTo(WorkOrderStatus next) {
		return switch (this) {
			case CREATED -> next == RELEASED;
			case RELEASED -> next == IN_PROGRESS;
			case IN_PROGRESS -> next == COMPLETED;
			case COMPLETED -> false;
		};
	}

}
