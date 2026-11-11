package com.minimes.equipment;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EquipmentMessageMapper {

	/** 처리한 적이 없으면 null */
	ProcessedMessage find(@Param("woId") long woId, @Param("eqpCd") String eqpCd,
			@Param("messageId") String messageId);

	void insert(@Param("woId") long woId, @Param("eqpCd") String eqpCd, @Param("messageId") String messageId,
			@Param("resultId") long resultId, @Param("requestBody") String requestBody,
			@Param("ackBody") String ackBody);

	/** 처리한 전문의 요청 본문과 그때 보낸 ACK 본문 */
	record ProcessedMessage(String requestBody, String ackBody) {
	}

}
