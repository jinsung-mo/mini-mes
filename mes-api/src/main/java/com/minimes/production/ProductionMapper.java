package com.minimes.production;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProductionMapper {

	List<Lot> findLots(long woId);

	List<ProductionResult> findResults(long woId);

	int nextLotSeq(String prefix);

	void insertLot(@Param("lotNo") String lotNo, @Param("plantCd") String plantCd, @Param("woId") long woId,
			@Param("itemCd") String itemCd, @Param("qty") int qty);

	long insertResult(@Param("plantCd") String plantCd, @Param("woId") long woId, @Param("procCd") String procCd,
			@Param("eqpCd") String eqpCd, @Param("lotNo") String lotNo, @Param("goodQty") int goodQty,
			@Param("defectQty") int defectQty, @Param("defectCd") String defectCd,
			@Param("workDate") LocalDate workDate, @Param("shiftCd") String shiftCd,
			@Param("reportedAt") OffsetDateTime reportedAt);

}
