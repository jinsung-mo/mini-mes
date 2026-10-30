package com.minimes.production;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Lot(String lotNo, String itemCd, int qty, String createdAt) {
}
