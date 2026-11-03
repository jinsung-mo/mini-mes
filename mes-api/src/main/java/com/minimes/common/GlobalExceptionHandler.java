package com.minimes.common;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 예외를 레거시와 같은 응답으로 바꾼다. 레거시 MesController의 Run(), Fail()이 하던 일이다.
 * <ul>
 * <li>업무 오류(BusinessException): HTTP 200 + 해당 resultCode</li>
 * <li>처리 중 예외: HTTP 200 + E999. 예외 내용은 서버 로그에만 남기고, 응답에는 로그를 찾을 참조 번호만 준다</li>
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

	/**
	 * 레거시는 예외 메시지를 그대로 응답했다 (레거시 문제점 6). Java로 옮기면서 더 나빠졌는데, MyBatis 예외 메시지에는
	 * 서버의 매퍼 파일 경로, 실행한 SQL, 중복된 키 값까지 들어 있어서 단말 화면에 그대로 보였다 (트러블 슈팅 3).
	 * 단말에는 참조 번호만 보여 주고, 같은 번호로 서버 로그에서 원인을 찾는다.
	 */
	@ExceptionHandler(Exception.class)
	public ApiResponse<Void> handleUnexpected(Exception e) {
		String ref = UUID.randomUUID().toString().substring(0, 8);
		log.error("처리 중 예외 [ref={}]", ref, e);
		return ApiResponse.fail(ErrorCode.E999, "unexpected error (ref: " + ref + ")");
	}

}
