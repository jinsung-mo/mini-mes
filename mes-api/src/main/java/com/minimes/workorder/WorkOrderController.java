package com.minimes.workorder;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.minimes.common.ApiResponse;

@RestController
@RequestMapping("/api/work-orders")
public class WorkOrderController {

	private final WorkOrderService workOrderService;

	public WorkOrderController(WorkOrderService workOrderService) {
		this.workOrderService = workOrderService;
	}

	/**
	 * status를 enum이 아니라 문자열로 받는다. enum으로 받으면 status=FOO 같은 값에 Spring이 400을 주지만,
	 * 레거시는 그냥 빈 목록을 돌려준다.
	 */
	@GetMapping
	public ApiResponse<List<WorkOrder>> getWorkOrders(@RequestParam(required = false) String plantCd,
			@RequestParam(required = false) String status) {
		return ApiResponse.ok(workOrderService.getWorkOrders(plantCd, status));
	}

	@GetMapping("/{id}")
	public ApiResponse<WorkOrder> getWorkOrder(@PathVariable long id) {
		return ApiResponse.ok(workOrderService.getWorkOrder(id));
	}

	@PostMapping
	public ApiResponse<CreatedWorkOrder> createWorkOrder(@RequestBody CreateWorkOrderRequest request) {
		return ApiResponse.ok(workOrderService.create(request));
	}

	@PostMapping("/{id}/release")
	public ApiResponse<WorkOrder> release(@PathVariable long id) {
		return ApiResponse.ok(workOrderService.release(id));
	}

	@PostMapping("/{id}/start")
	public ApiResponse<WorkOrder> start(@PathVariable long id) {
		return ApiResponse.ok(workOrderService.start(id));
	}

	@PostMapping("/{id}/complete")
	public ApiResponse<WorkOrder> complete(@PathVariable long id) {
		return ApiResponse.ok(workOrderService.complete(id));
	}

}
