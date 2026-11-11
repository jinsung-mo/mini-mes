package com.minimes.equipment;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 설비 게이트웨이 설정 (application.yml의 mes.equipment).
 *
 * @param port 받는 포트. 0이면 비어 있는 포트를 고른다 (테스트용)
 * @param bindAddress 받는 주소. WAS처럼 루프백에서만 받는다
 * @param readTimeout 이 시간 동안 아무것도 오지 않으면 연결을 닫는다. 설비는 이보다 짧은 간격으로 HBT를 보낸다
 * @param maxConnections 동시에 받는 연결 수. 넘치면 새 연결을 바로 닫는다
 */
@ConfigurationProperties("mes.equipment")
public record EquipmentProperties(int port, String bindAddress, Duration readTimeout, int maxConnections) {
}
