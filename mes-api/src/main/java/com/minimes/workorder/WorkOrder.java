package com.minimes.workorder;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 작업지시 응답. 날짜·시각은 레거시처럼 SQL에서 문자열로 만들어 온다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record WorkOrder(
		long woId,
		String plantCd,
		String woNo,
		String itemCd,
		int planQty,
		int goodQty,
		int defectQty,
		WorkOrderStatus status,
		String planDate,
		String startedAt,
		String completedAt) {
}
