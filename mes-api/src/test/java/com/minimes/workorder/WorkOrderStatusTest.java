package com.minimes.workorder;

import static com.minimes.workorder.WorkOrderStatus.COMPLETED;
import static com.minimes.workorder.WorkOrderStatus.CREATED;
import static com.minimes.workorder.WorkOrderStatus.IN_PROGRESS;
import static com.minimes.workorder.WorkOrderStatus.RELEASED;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 상태 전이 규칙은 DB 없이 테스트할 수 있다. 규칙을 enum 한곳에 모아 둔 덕분이다.
 */
class WorkOrderStatusTest {

	@Test
	void 정해진_순서로_한_단계씩_바뀐다() {
		assertTrue(CREATED.canChangeTo(RELEASED));
		assertTrue(RELEASED.canChangeTo(IN_PROGRESS));
		assertTrue(IN_PROGRESS.canChangeTo(COMPLETED));
	}

	@Test
	void 단계를_건너뛰거나_되돌릴_수_없다() {
		assertFalse(CREATED.canChangeTo(IN_PROGRESS));
		assertFalse(CREATED.canChangeTo(COMPLETED));
		assertFalse(RELEASED.canChangeTo(CREATED));
		assertFalse(IN_PROGRESS.canChangeTo(RELEASED));
		assertFalse(IN_PROGRESS.canChangeTo(IN_PROGRESS));
	}

	@Test
	void 완료된_작업지시는_더_바뀌지_않는다() {
		for (WorkOrderStatus next : WorkOrderStatus.values()) {
			assertFalse(COMPLETED.canChangeTo(next));
		}
	}

}
