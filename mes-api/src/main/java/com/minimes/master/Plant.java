package com.minimes.master;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 응답 DTO는 JSON 키를 snake_case(plant_cd)로 내보낸다. 레거시 응답 계약이 DB 컬럼명 그대로이기 때문이다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Plant(String plantCd, String plantNm, String timeZone) {
}
