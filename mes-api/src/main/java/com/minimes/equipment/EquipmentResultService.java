package com.minimes.equipment;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.minimes.common.BusinessException;
import com.minimes.common.ErrorCode;
import com.minimes.equipment.EquipmentMessageMapper.ProcessedMessage;
import com.minimes.equipment.protocol.MessageBody;
import com.minimes.production.ProductionService;
import com.minimes.production.ReportResultRequest;
import com.minimes.production.ReportedResult;
import com.minimes.workorder.WorkOrderService;

/**
 * 설비가 보낸 실적 전문(RSLT)을 처리하고 ACK 본문을 돌려준다. 업무 오류는 {@link BusinessException}으로 던진다.
 * <p>
 * 아래 순서가 하나의 트랜잭션이다.
 * <ol>
 * <li>작업지시 행 잠금: 같은 작업지시의 전문이 한 줄로 선다. 동시에 온 재전송도 앞의 커밋을 본다</li>
 * <li>중복 확인: 이미 처리한 메시지 ID면 저장해 둔 ACK를 그대로 돌려준다.
 * 업무 검증보다 먼저 해야 한다. 마지막 실적으로 작업지시가 완료된 뒤의 재전송이 E006을 받으면,
 * 설비는 실패한 줄 알고 계속 다시 보낸다</li>
 * <li>{@link ProductionService#report}: REST와 같은 검증, 채번, 수량 누적</li>
 * <li>메시지 ID와 ACK 본문 저장. 실적과 함께 커밋되고, 함께 롤백된다</li>
 * </ol>
 */
@Service
public class EquipmentResultService {

	private static final Logger log = LoggerFactory.getLogger(EquipmentResultService.class);

	private final ProductionService productionService;
	private final WorkOrderService workOrderService;
	private final EquipmentMessageMapper messageMapper;

	public EquipmentResultService(ProductionService productionService, WorkOrderService workOrderService,
			EquipmentMessageMapper messageMapper) {
		this.productionService = productionService;
		this.workOrderService = workOrderService;
		this.messageMapper = messageMapper;
	}

	@Transactional
	public String report(String messageId, String body) {
		ReportResultRequest request;
		try {
			request = ResultMessage.toRequest(MessageBody.parse(body));
		}
		catch (IllegalArgumentException e) {
			throw new BusinessException(ErrorCode.E001, "invalid body: " + e.getMessage());
		}

		// woId나 eqpCd가 없거나 작업지시가 없으면 처리된 적이 있을 수 없으므로 중복 확인을 건너뛴다.
		// 그러면 report가 REST와 같은 순서로 E001, E002, E004를 낸다.
		long woId = request.getWoId();
		String eqpCd = request.getEqpCd();
		if (woId > 0 && StringUtils.hasLength(eqpCd) && workOrderService.findWorkOrderForUpdate(woId) != null) {
			ProcessedMessage processed = messageMapper.find(woId, eqpCd, messageId);
			if (processed != null) {
				if (!processed.requestBody().equals(body)) {
					// 설비가 메시지 ID를 다시 쓴 것이다. 어느 쪽을 믿을지 정할 수 없으므로 반영하지 않는다
					throw new BusinessException(ErrorCode.E001, "message id already used for a different result");
				}
				log.info("이미 처리한 전문 [woId={}, eqpCd={}, messageId={}] → 처음 ACK를 다시 보냄", woId, eqpCd, messageId);
				return processed.ackBody();
			}
		}

		ReportedResult result = productionService.report(request);
		String ackBody = ackBody(result);
		messageMapper.insert(woId, eqpCd, messageId, result.resultId(), body, ackBody);
		return ackBody;
	}

	/** 키는 REST 응답 data와 같다 */
	private static String ackBody(ReportedResult result) {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("result_id", result.resultId());
		fields.put("lot_no", result.lotNo());
		fields.put("work_date", result.workDate());
		fields.put("shift_cd", result.shiftCd());
		fields.put("wo_good_qty", result.woGoodQty());
		fields.put("wo_status", result.woStatus());
		return MessageBody.format(fields);
	}

}
