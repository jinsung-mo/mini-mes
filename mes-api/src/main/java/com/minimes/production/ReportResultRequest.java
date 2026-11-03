package com.minimes.production;

/**
 * 실적 보고 요청. {@code CreateWorkOrderRequest}와 같은 이유로 record가 아니라 "기본 생성자 + setter" 클래스다.
 * woId, goodQty, defectQty 키가 없으면 0으로 남아서 레거시처럼 E001, E002로 응답한다.
 */
public class ReportResultRequest {

	private long woId;
	private String procCd;
	private String eqpCd;
	private int goodQty;
	private int defectQty;
	private String defectCd;
	/** 단말이 보낸 시각. 없으면 서버 현재 시각 */
	private String reportedAt;

	public long getWoId() {
		return woId;
	}

	public void setWoId(long woId) {
		this.woId = woId;
	}

	public String getProcCd() {
		return procCd;
	}

	public void setProcCd(String procCd) {
		this.procCd = procCd;
	}

	public String getEqpCd() {
		return eqpCd;
	}

	public void setEqpCd(String eqpCd) {
		this.eqpCd = eqpCd;
	}

	public int getGoodQty() {
		return goodQty;
	}

	public void setGoodQty(int goodQty) {
		this.goodQty = goodQty;
	}

	public int getDefectQty() {
		return defectQty;
	}

	public void setDefectQty(int defectQty) {
		this.defectQty = defectQty;
	}

	public String getDefectCd() {
		return defectCd;
	}

	public void setDefectCd(String defectCd) {
		this.defectCd = defectCd;
	}

	public String getReportedAt() {
		return reportedAt;
	}

	public void setReportedAt(String reportedAt) {
		this.reportedAt = reportedAt;
	}

}
