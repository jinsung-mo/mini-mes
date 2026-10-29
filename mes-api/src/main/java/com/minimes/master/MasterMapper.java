package com.minimes.master;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Data Access 계층. 메서드 이름과 같은 id의 SQL이 resources/mapper/MasterMapper.xml에 있다.
 */
@Mapper
public interface MasterMapper {

	List<Plant> findPlants();

	List<Item> findItems(String plantCd);

	List<Routing> findRoutings(@Param("plantCd") String plantCd, @Param("itemCd") String itemCd);

	List<Equipment> findEquipments(String plantCd);

	List<DefectCode> findDefectCodes();

}
