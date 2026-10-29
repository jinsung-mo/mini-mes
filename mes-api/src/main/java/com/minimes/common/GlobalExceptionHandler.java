package com.minimes.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 예외를 레거시와 같은 응답으로 바꾼다. 레거시 MesController의 Run(), Fail()이 하던 일이다.
 * <ul>
 * <li>업무 오류(BusinessException): HTTP 200 + 해당 resultCode</li>
 * <li>처리 중 예외: HTTP 200 + E999 + 예외 메시지 (레거시 문제점 6. 메시지 정제는 이후 단계에서)</li>
 * <li>없는 주소, 지원하지 않는 메서드 같은 HTTP 수준 오류: 레거시처럼 4xx 상태 코드.
 * ResponseEntityExceptionHandler를 상속해야 이 오류들이 아래 E999 처리기로 빠지지 않는다.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(BusinessException.class)
	public ApiResponse<Void> handleBusiness(BusinessException e) {
		return ApiResponse.fail(e.getErrorCode(), e.getMessage());
	}

	@ExceptionHandler(Exception.class)
	public ApiResponse<Void> handleUnexpected(Exception e) {
		log.error("처리 중 예외", e);
		return ApiResponse.fail(ErrorCode.E999, e.getMessage());
	}

}
