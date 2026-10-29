package com.minimes.master;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.minimes.common.ApiResponse;

/**
 * Presentation 계층. HTTP 요청을 받아 Service를 부르고, 결과를 공통 응답 형식으로 감싸기만 한다.
 * 필수값 검증도 Service에서 하므로 파라미터는 모두 required = false로 받는다.
 */
@RestController
@RequestMapping("/api")
public class MasterController {

	private final MasterService masterService;

	public MasterController(MasterService masterService) {
		this.masterService = masterService;
	}

	@GetMapping("/plants")
	public ApiResponse<List<Plant>> getPlants() {
		return ApiResponse.ok(masterService.getPlants());
	}

	@GetMapping("/items")
	public ApiResponse<List<Item>> getItems(@RequestParam(required = false) String plantCd) {
		return ApiResponse.ok(masterService.getItems(plantCd));
	}

	@GetMapping("/routings")
	public ApiResponse<List<Routing>> getRoutings(@RequestParam(required = false) String plantCd,
			@RequestParam(required = false) String itemCd) {
		return ApiResponse.ok(masterService.getRoutings(plantCd, itemCd));
	}

	@GetMapping("/equipments")
	public ApiResponse<List<Equipment>> getEquipments(@RequestParam(required = false) String plantCd) {
		return ApiResponse.ok(masterService.getEquipments(plantCd));
	}

	@GetMapping("/defect-codes")
	public ApiResponse<List<DefectCode>> getDefectCodes() {
		return ApiResponse.ok(masterService.getDefectCodes());
	}

}
