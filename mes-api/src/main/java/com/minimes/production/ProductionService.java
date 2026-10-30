package com.minimes.production;

import java.util.List;

import org.springframework.stereotype.Service;

/**
 * LOT과 생산실적. 레거시처럼 없는 작업지시를 조회하면 오류가 아니라 빈 목록을 돌려준다.
 */
@Service
public class ProductionService {

	private final ProductionMapper productionMapper;

	public ProductionService(ProductionMapper productionMapper) {
		this.productionMapper = productionMapper;
	}

	public List<Lot> getLots(long woId) {
		return productionMapper.findLots(woId);
	}

	public List<ProductionResult> getResults(long woId) {
		return productionMapper.findResults(woId);
	}

}
