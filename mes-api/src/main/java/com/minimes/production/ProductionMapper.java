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

	/** 공장·근무일자별 다음 LOT 순번. 호출한 트랜잭션이 끝날 때까지 그 공장·근무일자의 채번을 잠근다 */
	int nextLotSeq(@Param("plantCd") String plantCd, @Param("workDate") LocalDate workDate,
			@Param("prefix") String prefix);

	void insertLot(@Param("lotNo") String lotNo, @Param("plantCd") String plantCd, @Param("woId") long woId,
			@Param("itemCd") String itemCd, @Param("qty") int qty);

	long insertResult(@Param("plantCd") String plantCd, @Param("woId") long woId, @Param("procCd") String procCd,
			@Param("eqpCd") String eqpCd, @Param("lotNo") String lotNo, @Param("goodQty") int goodQty,
			@Param("defectQty") int defectQty, @Param("defectCd") String defectCd,
			@Param("workDate") LocalDate workDate, @Param("shiftCd") String shiftCd,
			@Param("reportedAt") OffsetDateTime reportedAt);

}
