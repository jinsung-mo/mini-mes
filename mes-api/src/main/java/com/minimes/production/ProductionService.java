package com.minimes.production;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.minimes.common.BusinessException;
import com.minimes.common.ErrorCode;
import com.minimes.master.MasterService;
import com.minimes.workorder.WorkOrder;
import com.minimes.workorder.WorkOrderProgress;
import com.minimes.workorder.WorkOrderService;
import com.minimes.workorder.WorkOrderStatus;

/**
 * LOT과 생산실적. 레거시처럼 없는 작업지시를 조회하면 오류가 아니라 빈 목록을 돌려준다.
 */
@Service
public class ProductionService {

	private static final DateTimeFormatter LOT_NO_DATE = DateTimeFormatter.ofPattern("yyMMdd");

	private final ProductionMapper productionMapper;
	private final WorkOrderService workOrderService;
	private final MasterService masterService;

	/** 레거시처럼 서버 시간대로 근무조를 계산한다 (레거시 문제점 2) */
	private final ZoneId serverZone = ZoneId.systemDefault();

	public ProductionService(ProductionMapper productionMapper, WorkOrderService workOrderService,
			MasterService masterService) {
		this.productionMapper = productionMapper;
		this.workOrderService = workOrderService;
		this.masterService = masterService;
	}

	public List<Lot> getLots(long woId) {
		return productionMapper.findLots(woId);
	}

	public List<ProductionResult> getResults(long woId) {
		return productionMapper.findResults(woId);
	}

	/**
	 * 실적 보고. 검증 순서는 레거시와 같다.
	 * 필수값(E001) → 수량(E002) → 보고 시각(E001) → 작업지시(E004, E006) → 설비(E009) → 불량 코드(E007, E008)
	 * <p>
	 * LOT 저장, 실적 저장, 작업지시 수량 누적, 자동 완료가 하나의 트랜잭션이다. 중간에 예외가 나면 모두 롤백된다.
	 */
	@Transactional
	public ReportedResult report(ReportResultRequest request) {
		if (request.getWoId() <= 0 || !StringUtils.hasLength(request.getProcCd())
				|| !StringUtils.hasLength(request.getEqpCd())) {
			throw new BusinessException(ErrorCode.E001, "woId, procCd, eqpCd are required");
		}
		int goodQty = request.getGoodQty();
		int defectQty = request.getDefectQty();
		if (goodQty < 0 || defectQty < 0 || goodQty + defectQty == 0) {
			throw new BusinessException(ErrorCode.E002, "invalid quantity");
		}
		LocalDateTime reportedAt = resolveReportedAt(request.getReportedAt());

		WorkOrder workOrder = workOrderService.getWorkOrder(request.getWoId());
		if (workOrder.status() != WorkOrderStatus.IN_PROGRESS) {
			throw new BusinessException(ErrorCode.E006, "work order is not in progress");
		}
		if (!masterService.existsEquipment(workOrder.plantCd(), request.getProcCd(), request.getEqpCd())) {
			throw new BusinessException(ErrorCode.E009, "equipment not found");
		}
		String defectCd = StringUtils.hasLength(request.getDefectCd()) ? request.getDefectCd() : null;
		if (defectQty > 0 && defectCd == null) {
			throw new BusinessException(ErrorCode.E007, "defectCd is required when defectQty > 0");
		}
		if (defectCd != null && !masterService.existsDefectCode(defectCd)) {
			throw new BusinessException(ErrorCode.E008, "defect code not found");
		}

		WorkShift shift = WorkShift.of(reportedAt);

		String lotNo = null;
		if (goodQty > 0) {
			// 채번: L-{공장}-{근무일자}-{순번4}
			String prefix = "L-" + workOrder.plantCd() + "-" + shift.workDate().format(LOT_NO_DATE) + "-";
			int seq = productionMapper.nextLotSeq(workOrder.plantCd(), shift.workDate(), prefix);
			lotNo = prefix + String.format("%04d", seq);
			productionMapper.insertLot(lotNo, workOrder.plantCd(), workOrder.woId(), workOrder.itemCd(), goodQty);
		}

		long resultId = productionMapper.insertResult(workOrder.plantCd(), workOrder.woId(), request.getProcCd(),
				request.getEqpCd(), lotNo, goodQty, defectQty, defectCd, shift.workDate(), shift.shiftCd(),
				reportedAt.atZone(serverZone).toOffsetDateTime());

		WorkOrderProgress progress = workOrderService.applyResult(workOrder, goodQty, defectQty);

		return new ReportedResult(resultId, lotNo, shift.workDate().toString(), shift.shiftCd(),
				progress.goodQty(), progress.status());
	}

	private LocalDateTime resolveReportedAt(String value) {
		if (!StringUtils.hasLength(value)) {
			return LocalDateTime.now(serverZone);
		}
		LocalDateTime parsed = ReportedAtParser.toServerLocal(value, serverZone);
		if (parsed == null) {
			throw new BusinessException(ErrorCode.E001, "reportedAt is invalid");
		}
		return parsed;
	}

}
