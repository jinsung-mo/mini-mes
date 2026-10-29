package com.minimes.master;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.minimes.common.BusinessException;
import com.minimes.common.ErrorCode;

/**
 * Business 계층. 입력 검증과 업무 규칙은 여기에 둔다.
 * 요청이 REST로 오든 TCP로 오든 같은 규칙이 적용되어야 하기 때문이다.
 */
@Service
public class MasterService {

	private final MasterMapper masterMapper;

	public MasterService(MasterMapper masterMapper) {
		this.masterMapper = masterMapper;
	}

	public List<Plant> getPlants() {
		return masterMapper.findPlants();
	}

	public List<Item> getItems(String plantCd) {
		if (!StringUtils.hasLength(plantCd)) {
			throw new BusinessException(ErrorCode.E001, "plantCd is required");
		}
		return masterMapper.findItems(plantCd);
	}

	public List<Routing> getRoutings(String plantCd, String itemCd) {
		if (!StringUtils.hasLength(plantCd) || !StringUtils.hasLength(itemCd)) {
			throw new BusinessException(ErrorCode.E001, "plantCd and itemCd are required");
		}
		return masterMapper.findRoutings(plantCd, itemCd);
	}

	public List<Equipment> getEquipments(String plantCd) {
		if (!StringUtils.hasLength(plantCd)) {
			throw new BusinessException(ErrorCode.E001, "plantCd is required");
		}
		return masterMapper.findEquipments(plantCd);
	}

	public List<DefectCode> getDefectCodes() {
		return masterMapper.findDefectCodes();
	}

}
