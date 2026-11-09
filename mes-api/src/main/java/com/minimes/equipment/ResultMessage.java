package com.minimes.equipment;

import java.util.Map;
import java.util.function.Function;

import com.minimes.common.BusinessException;
import com.minimes.common.ErrorCode;
import com.minimes.production.ReportResultRequest;

/**
 * 실적 전문(RSLT) 본문을 REST와 같은 {@link ReportResultRequest}로 바꾼다.
 * 바꾼 뒤에는 REST와 같은 {@code ProductionService.report}를 거치므로, 업무 검증(E001~E009)은 여기서 하지 않는다.
 * <p>
 * 키 이름은 REST 요청 JSON과 같다. REST와 맞춘 규칙:
 * <ul>
 * <li>키가 없으면 REST에서 JSON 키가 없을 때처럼 숫자는 0, 문자열은 null로 둔다. 그래서 Service가 E001, E002로 답한다</li>
 * <li>모르는 키는 무시한다 (Spring의 JSON 해석 기본값과 같음)</li>
 * </ul>
 * REST와 다른 규칙:
 * <ul>
 * <li>숫자 칸에 숫자가 아닌 값("10.5", "abc", 빈 값)이 오면 E001이다. REST는 HTTP 400으로 답하지만,
 * TCP에는 HTTP 상태가 없으므로 같은 의미의 에러 코드로 NAK를 보낸다</li>
 * <li>키 이름의 대소문자를 구분한다. 새로 만드는 인터페이스라서 레거시(ASP.NET)의 대소문자 무시를 따를 이유가 없다</li>
 * </ul>
 */
public final class ResultMessage {

	private ResultMessage() {
	}

	public static ReportResultRequest toRequest(Map<String, String> fields) {
		ReportResultRequest request = new ReportResultRequest();
		if (fields.containsKey("woId")) {
			request.setWoId(parse(fields, "woId", Long::parseLong));
		}
		request.setProcCd(fields.get("procCd"));
		request.setEqpCd(fields.get("eqpCd"));
		if (fields.containsKey("goodQty")) {
			request.setGoodQty(parse(fields, "goodQty", Integer::parseInt));
		}
		if (fields.containsKey("defectQty")) {
			request.setDefectQty(parse(fields, "defectQty", Integer::parseInt));
		}
		request.setDefectCd(fields.get("defectCd"));
		request.setReportedAt(fields.get("reportedAt"));
		return request;
	}

	/** 정수가 아니거나 범위(int, long)를 넘으면 E001 */
	private static <T> T parse(Map<String, String> fields, String key, Function<String, T> parser) {
		try {
			return parser.apply(fields.get(key));
		}
		catch (NumberFormatException e) {
			throw new BusinessException(ErrorCode.E001, key + " must be an integer");
		}
	}

}
