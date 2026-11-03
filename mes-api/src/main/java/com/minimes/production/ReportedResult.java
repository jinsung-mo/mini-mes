package com.minimes.production;

import com.minimes.workorder.WorkOrderStatus;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 실적 보고 응답. lotNo는 양품이 0이면 null이다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ReportedResult(
		long resultId,
		String lotNo,
		String workDate,
		String shiftCd,
		int woGoodQty,
		WorkOrderStatus woStatus) {
}
