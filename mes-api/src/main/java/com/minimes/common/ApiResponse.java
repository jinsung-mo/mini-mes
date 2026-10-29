package com.minimes.common;

/**
 * 모든 API 응답의 공통 형식. 레거시와 같은 계약을 지킨다.
 * 성공·실패 모두 HTTP 200이고, 결과는 resultCode로 구분한다.
 */
public record ApiResponse<T>(String resultCode, String message, T data) {

	private static final String SUCCESS = "0000";

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(SUCCESS, "OK", data);
	}

	public static ApiResponse<Void> fail(ErrorCode errorCode, String message) {
		return new ApiResponse<>(errorCode.name(), message, null);
	}

}
