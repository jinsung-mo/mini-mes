package com.minimes.production;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 실적 이력 한 건. lotNo(양품이 0이면 LOT 없음)와 defectCd(불량이 없으면 없음)는 null일 수 있다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProductionResult(
		long resultId,
		String procCd,
		String eqpCd,
		String lotNo,
		int goodQty,
		int defectQty,
		String defectCd,
		String workDate,
		String shiftCd,
		String reportedAt) {
}
