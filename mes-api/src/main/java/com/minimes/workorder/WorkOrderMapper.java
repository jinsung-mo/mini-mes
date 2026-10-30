package com.minimes.workorder;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkOrderMapper {

	List<WorkOrder> findByPlant(@Param("plantCd") String plantCd, @Param("status") String status);

	WorkOrder findById(long woId);

	WorkOrderStatus findStatus(long woId);

	int nextWoSeq(String prefix);

	long insert(@Param("plantCd") String plantCd, @Param("woNo") String woNo, @Param("itemCd") String itemCd,
			@Param("planQty") int planQty, @Param("planDate") LocalDate planDate);

	void updateStatus(@Param("woId") long woId, @Param("status") WorkOrderStatus status);

}
