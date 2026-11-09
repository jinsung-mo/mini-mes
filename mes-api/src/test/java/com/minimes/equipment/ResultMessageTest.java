package com.minimes.equipment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.minimes.common.BusinessException;
import com.minimes.common.ErrorCode;
import com.minimes.equipment.protocol.MessageBody;
import com.minimes.production.ReportResultRequest;

class ResultMessageTest {

	@Test
	void 본문을_REST와_같은_요청으로_바꾼다() {
		ReportResultRequest request = ResultMessage.toRequest(MessageBody.parse(
				"woId=15|procCd=BODY|eqpCd=EQ-BODY-01|goodQty=1|defectQty=2|defectCd=D01|reportedAt=2026-11-09T08:00"));

		assertEquals(15, request.getWoId());
		assertEquals("BODY", request.getProcCd());
		assertEquals("EQ-BODY-01", request.getEqpCd());
		assertEquals(1, request.getGoodQty());
		assertEquals(2, request.getDefectQty());
		assertEquals("D01", request.getDefectCd());
		assertEquals("2026-11-09T08:00", request.getReportedAt());
	}

	@Test
	void 없는_키는_REST에서_JSON_키가_없을_때와_같다() {
		// 업무 검증은 Service가 한다. 여기서는 woId=0 → E001, 수량 0 → E002가 되도록 기본값만 둔다
		ReportResultRequest request = ResultMessage.toRequest(Map.of("procCd", "BODY", "unknown", "x"));

		assertEquals(0, request.getWoId());
		assertEquals(0, request.getGoodQty());
		assertEquals(0, request.getDefectQty());
		assertNull(request.getEqpCd());
		assertNull(request.getReportedAt());
	}

	@Test
	void 숫자_칸에_정수가_아니면_E001() {
		for (String body : new String[] { "goodQty=10.5", "goodQty=abc", "goodQty=", "goodQty=2147483648",
				"woId=x" }) {
			BusinessException e = assertThrows(BusinessException.class,
					() -> ResultMessage.toRequest(MessageBody.parse(body)), body);
			assertEquals(ErrorCode.E001, e.getErrorCode(), body);
		}
	}

	@Test
	void 키_이름의_대소문자를_구분한다() {
		assertEquals(0, ResultMessage.toRequest(Map.of("WoId", "15")).getWoId());
	}

}
