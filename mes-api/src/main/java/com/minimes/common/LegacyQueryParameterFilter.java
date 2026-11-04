package com.minimes.common;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 쿼리 파라미터를 레거시(ASP.NET 모델 바인딩)와 같은 규칙으로 읽게 한다.
 * <ul>
 * <li>이름의 대소문자를 가리지 않는다. ?plantcd=KR01도 plantCd로 받는다.
 * Spring의 {@code @RequestParam}은 대소문자를 구분해서, plantCd가 없다고 보고 E001로 응답했다</li>
 * <li>같은 이름이 여러 번 오면 첫 값을 쓴다. ?plantCd=KR01&amp;plantCd=US01은 KR01이다.
 * Spring은 두 값을 "KR01,US01"로 이어 붙여서 빈 목록을 돌려줬다</li>
 * </ul>
 * 레거시 API에는 값을 여러 개 받는 파라미터가 없어서 모든 파라미터에 첫 값만 남긴다. 그런 파라미터가 생기면 이 규칙을 다시 정한다.
 */
public class LegacyQueryParameterFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		chain.doFilter(new LegacyParameterRequest(request), response);
	}

	private static final class LegacyParameterRequest extends HttpServletRequestWrapper {

		private final Map<String, String[]> parameters;

		LegacyParameterRequest(HttpServletRequest request) {
			super(request);
			// 대소문자만 다른 이름은 한 이름으로 보고, 먼저 나온 값을 남긴다
			Map<String, String[]> parameters = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
			request.getParameterMap().forEach((name, values) -> {
				if (values.length > 0) {
					parameters.putIfAbsent(name, new String[] { values[0] });
				}
			});
			this.parameters = Collections.unmodifiableMap(parameters);
		}

		@Override
		public String getParameter(String name) {
			String[] values = this.parameters.get(name);
			return (values != null) ? values[0] : null;
		}

		@Override
		public String[] getParameterValues(String name) {
			String[] values = this.parameters.get(name);
			return (values != null) ? values.clone() : null;
		}

		@Override
		public Map<String, String[]> getParameterMap() {
			return this.parameters;
		}

		@Override
		public Enumeration<String> getParameterNames() {
			return Collections.enumeration(this.parameters.keySet());
		}

	}

}
