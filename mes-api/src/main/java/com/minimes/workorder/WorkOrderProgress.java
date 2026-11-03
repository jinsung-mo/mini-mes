package com.minimes.workorder;

/**
 * 실적을 반영한 뒤의 작업지시 누적 양품 수량과 상태.
 */
public record WorkOrderProgress(int goodQty, WorkOrderStatus status) {
}
