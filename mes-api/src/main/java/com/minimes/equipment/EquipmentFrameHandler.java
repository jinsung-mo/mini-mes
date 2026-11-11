package com.minimes.equipment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.minimes.common.BusinessException;
import com.minimes.common.ErrorCode;
import com.minimes.equipment.protocol.Frame;
import com.minimes.equipment.protocol.MessageBody;

/**
 * 전문 종류별 처리. REST의 Controller와 GlobalExceptionHandler가 하는 일을 합친 자리다.
 * <ul>
 * <li>RSLT: 실적 처리 → ACK(처리 결과) 또는 NAK(에러 코드)</li>
 * <li>HBT: 빈 ACK</li>
 * <li>ACK, NAK: 설비가 보낼 전문이 아니다. 응답하면 설비도 NAK로 답해서 서로 끝없이 주고받을 수 있으므로 로그만 남긴다</li>
 * <li>그 밖의 종류: NAK E001</li>
 * </ul>
 */
@Component
public class EquipmentFrameHandler implements FrameHandler {

	private static final Logger log = LoggerFactory.getLogger(EquipmentFrameHandler.class);

	private final EquipmentResultService resultService;

	public EquipmentFrameHandler(EquipmentResultService resultService) {
		this.resultService = resultService;
	}

	@Override
	public Frame handle(Frame request) {
		String id = request.messageId();
		switch (request.type()) {
			case Frame.RESULT:
				return handleResult(request);
			case Frame.HEARTBEAT:
				return new Frame(Frame.ACK, id, "");
			case Frame.ACK:
			case Frame.NAK:
				log.warn("설비가 보낸 {} 전문은 처리하지 않음 [messageId={}]", request.type(), id);
				return null;
			default:
				return nak(id, ErrorCode.E001, "unknown type: " + request.type());
		}
	}

	private Frame handleResult(Frame request) {
		String id = request.messageId();
		try {
			return new Frame(Frame.ACK, id, resultService.report(id, request.body()));
		}
		catch (BusinessException e) {
			return nak(id, e.getErrorCode(), e.getMessage());
		}
		catch (RuntimeException e) {
			// REST의 E999와 같다: 예외 내용은 서버 로그에만, 설비에는 참조 번호만 (트러블 슈팅 3)
			String ref = UUID.randomUUID().toString().substring(0, 8);
			log.error("설비 전문 처리 중 예외 [ref={}, messageId={}]", ref, id, e);
			return nak(id, ErrorCode.E999, "unexpected error (ref: " + ref + ")");
		}
	}

	/** NAK 본문: code=E006|message=... 메시지에 구분자나 제어 문자가 있으면 공백으로 바꾼다 */
	static Frame nak(String messageId, ErrorCode code, String message) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("code", code);
		fields.put("message", message == null ? "" : message.replaceAll("[|\\p{Cntrl}]", " "));
		return new Frame(Frame.NAK, messageId, MessageBody.format(fields));
	}

}
