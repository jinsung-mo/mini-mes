package com.minimes.equipment;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 설비 게이트웨이 설정 (application.yml의 mes.equipment).
 *
 * @param port 받는 포트. 0이면 비어 있는 포트를 고른다 (테스트용)
 * @param bindAddresses 받는 주소. WAS처럼 루프백(127.0.0.1, ::1)에서만 받는다. 포트는 모든 주소가 같다
 * @param readTimeout 이 시간 동안 아무것도 오지 않으면 연결을 닫는다. 설비는 이보다 짧은 간격으로 HBT를 보낸다
 * @param maxConnections 동시에 받는 연결 수. 넘치면 새 연결을 바로 닫는다
 */
@ConfigurationProperties("mes.equipment")
public record EquipmentProperties(int port, List<String> bindAddresses, Duration readTimeout, int maxConnections) {
}
