package com.minimes.common;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.UrlHandlerFilter;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.pattern.PathPatternParser;

import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * 요청을 레거시(ASP.NET)와 같은 규칙으로 해석하게 하는 설정.
 * <p>
 * 3단계 동등성 검증(scripts/equivalence.py)에서 같은 요청에 두 WAS가 다르게 답한 것들이다. 모두 ASP.NET과 Spring의 기본 동작 차이다.
 * <ul>
 * <li>주소 대소문자를 가리지 않는다: /API/Plants</li>
 * <li>주소 끝의 슬래시를 무시한다: /api/plants/</li>
 * <li>쿼리 파라미터 이름의 대소문자를 가리지 않고, 같은 이름이 여러 번 오면 첫 값을 쓴다 ({@link LegacyQueryParameterFilter})</li>
 * <li>JSON 문자열 칸에 숫자나 true/false가 오면 400으로 거절한다</li>
 * </ul>
 * 앞의 셋은 레거시가 받던 요청을 Java가 거절하던 것이라 그대로 두면 지금 쓰는 클라이언트가 깨진다.
 * 마지막은 반대로 레거시가 거절하던 요청을 Java가 처리하던 것이다. 레거시에서 오류였던 요청으로 Java에서는 데이터가 만들어질 수 있다.
 * <p>
 * JSON 키 대소문자와 숫자 칸의 소수 거절은 application.yml의 spring.jackson 설정에 있다 (2단계에서 맞춤).
 */
@Configuration
public class LegacyRequestConfig implements WebMvcConfigurer {

	@Override
	public void configurePathMatch(PathMatchConfigurer configurer) {
		PathPatternParser parser = new PathPatternParser();
		parser.setCaseSensitive(false);
		configurer.setPatternParser(parser);
	}

	/**
	 * Spring 6부터는 끝에 슬래시가 붙은 주소를 다른 주소로 봐서 404를 준다. 슬래시를 뗀 주소로 바꿔서 넘긴다.
	 * 리다이렉트로 돌려보내지 않는 이유는, 레거시처럼 한 번에 처리해야 POST도 그대로 동작하기 때문이다.
	 */
	@Bean
	UrlHandlerFilter trailingSlashFilter() {
		return UrlHandlerFilter.trailingSlashHandler("/**").wrapRequest().build();
	}

	@Bean
	LegacyQueryParameterFilter legacyQueryParameterFilter() {
		return new LegacyQueryParameterFilter();
	}

	/**
	 * Jackson은 문자열 칸에 온 숫자·true/false를 문자열로 바꿔 받는다("plantCd": 1 → "1"). 레거시(System.Text.Json)는 400이다.
	 * 반대 방향인 숫자 칸의 문자열("planQty": "4")은 레거시도 받으므로 그대로 둔다.
	 */
	@Bean
	JsonMapperBuilderCustomizer rejectScalarsInTextFields() {
		return builder -> builder.withCoercionConfig(LogicalType.Textual, config -> config
				.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
				.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
				.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
	}

}
