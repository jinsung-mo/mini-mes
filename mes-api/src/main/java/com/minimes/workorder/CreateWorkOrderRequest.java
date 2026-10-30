package com.minimes.workorder;

/**
 * 작업지시 생성 요청. 요청 JSON 키는 camelCase(plantCd)다.
 * <p>
 * 응답 DTO와 달리 record가 아니라 "기본 생성자 + setter" 클래스다. 레거시(C#) 요청 클래스와 같은 방식이어야
 * 빠진 값을 똑같이 처리하기 때문이다.
 * <ul>
 * <li>planQty 키가 없으면: setter가 불리지 않아 0으로 남고, 그래서 E002 (레거시와 같음)</li>
 * <li>record였다면: 생성자에 넣을 planQty 값이 없어서 요청 자체가 400으로 실패한다</li>
 * <li>planQty가 null이면: int에 null을 넣을 수 없어서 400 (레거시와 같음)</li>
 * </ul>
 */
public class CreateWorkOrderRequest {

	private String plantCd;
	private String itemCd;
	private int planQty;
	private String planDate;

	public String getPlantCd() {
		return plantCd;
	}

	public void setPlantCd(String plantCd) {
		this.plantCd = plantCd;
	}

	public String getItemCd() {
		return itemCd;
	}

	public void setItemCd(String itemCd) {
		this.itemCd = itemCd;
	}

	public int getPlanQty() {
		return planQty;
	}

	public void setPlanQty(int planQty) {
		this.planQty = planQty;
	}

	public String getPlanDate() {
		return planDate;
	}

	public void setPlanDate(String planDate) {
		this.planDate = planDate;
	}

}
