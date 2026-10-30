package com.minimes.workorder;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.minimes.common.BusinessException;
import com.minimes.common.ErrorCode;
import com.minimes.master.MasterService;

@Service
public class WorkOrderService {

	// C#의 TryParseExact("yyyy-MM-dd")처럼 형식과 날짜를 엄격하게 본다 (2026-02-30, 2026-1-5는 실패).
	// yyyy가 아니라 uuuu인 이유: STRICT 모드에서 yyyy는 연호(era)까지 요구해서 항상 실패한다.
	private static final DateTimeFormatter PLAN_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd")
			.withResolverStyle(ResolverStyle.STRICT);

	private static final DateTimeFormatter WO_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

	private final WorkOrderMapper workOrderMapper;
	private final MasterService masterService;

	public WorkOrderService(WorkOrderMapper workOrderMapper, MasterService masterService) {
		this.workOrderMapper = workOrderMapper;
		this.masterService = masterService;
	}

	public List<WorkOrder> getWorkOrders(String plantCd, String status) {
		if (!StringUtils.hasLength(plantCd)) {
			throw new BusinessException(ErrorCode.E001, "plantCd is required");
		}
		return workOrderMapper.findByPlant(plantCd, status);
	}

	public WorkOrder getWorkOrder(long woId) {
		WorkOrder workOrder = workOrderMapper.findById(woId);
		if (workOrder == null) {
			throw new BusinessException(ErrorCode.E004, "work order not found");
		}
		return workOrder;
	}

	/**
	 * 검증 순서도 레거시와 같아야 한다. 여러 값이 동시에 틀렸을 때 같은 에러 코드가 나가야 하기 때문이다.
	 * 필수값(E001) → 계획일 형식(E001) → 계획 수량(E002) → 품목 존재(E003)
	 */
	public CreatedWorkOrder create(CreateWorkOrderRequest request) {
		if (!StringUtils.hasLength(request.getPlantCd()) || !StringUtils.hasLength(request.getItemCd())) {
			throw new BusinessException(ErrorCode.E001, "plantCd and itemCd are required");
		}
		LocalDate planDate = parsePlanDate(request.getPlanDate());
		if (request.getPlanQty() <= 0) {
			throw new BusinessException(ErrorCode.E002, "planQty must be positive");
		}
		if (!masterService.existsItem(request.getPlantCd(), request.getItemCd())) {
			throw new BusinessException(ErrorCode.E003, "item not found");
		}

		// 채번: WO-{공장}-{계획일}-{순번3}
		String prefix = "WO-" + request.getPlantCd() + "-" + planDate.format(WO_NO_DATE) + "-";
		String woNo = prefix + String.format("%03d", workOrderMapper.nextWoSeq(prefix));

		long woId = workOrderMapper.insert(request.getPlantCd(), woNo, request.getItemCd(), request.getPlanQty(),
				planDate);
		return new CreatedWorkOrder(woId, woNo);
	}

	public WorkOrder release(long woId) {
		return changeStatus(woId, WorkOrderStatus.RELEASED);
	}

	public WorkOrder start(long woId) {
		return changeStatus(woId, WorkOrderStatus.IN_PROGRESS);
	}

	public WorkOrder complete(long woId) {
		return changeStatus(woId, WorkOrderStatus.COMPLETED);
	}

	private WorkOrder changeStatus(long woId, WorkOrderStatus next) {
		WorkOrderStatus current = workOrderMapper.findStatus(woId);
		if (current == null) {
			throw new BusinessException(ErrorCode.E004, "work order not found");
		}
		if (!current.canChangeTo(next)) {
			throw new BusinessException(ErrorCode.E005, "invalid status transition: " + current + " -> " + next);
		}
		workOrderMapper.updateStatus(woId, next);
		return workOrderMapper.findById(woId);
	}

	private static LocalDate parsePlanDate(String value) {
		if (value != null) {
			try {
				return LocalDate.parse(value, PLAN_DATE);
			}
			catch (DateTimeParseException e) {
				// 아래에서 E001로 응답
			}
		}
		throw new BusinessException(ErrorCode.E001, "planDate must be yyyy-MM-dd");
	}

}
