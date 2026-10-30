package com.minimes.production;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.minimes.common.ApiResponse;

/**
 * 주소는 /api/work-orders/{id}/... 이지만, 다루는 데이터가 LOT과 생산실적이라 production 패키지에 둔다.
 */
@RestController
@RequestMapping("/api")
public class ProductionController {

	private final ProductionService productionService;

	public ProductionController(ProductionService productionService) {
		this.productionService = productionService;
	}

	@GetMapping("/work-orders/{id}/lots")
	public ApiResponse<List<Lot>> getLots(@PathVariable long id) {
		return ApiResponse.ok(productionService.getLots(id));
	}

	@GetMapping("/work-orders/{id}/results")
	public ApiResponse<List<ProductionResult>> getResults(@PathVariable long id) {
		return ApiResponse.ok(productionService.getResults(id));
	}

}
