package com.minimes.common;

/**
 * 업무 규칙 위반. {@link GlobalExceptionHandler}가 HTTP 200 + resultCode 응답으로 바꾼다.
 */
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	public BusinessException(ErrorCode errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}

}
