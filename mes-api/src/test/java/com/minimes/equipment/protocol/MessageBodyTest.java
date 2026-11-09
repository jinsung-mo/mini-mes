package com.minimes.equipment.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MessageBodyTest {

	@Test
	void 항목을_순서대로_읽는다() {
		Map<String, String> fields = MessageBody.parse("woId=15|defectCd=|reportedAt=2026-11-09T08:00:00+09:00");
		assertEquals(List.of("woId", "defectCd", "reportedAt"), List.copyOf(fields.keySet()));
		assertEquals("", fields.get("defectCd"), "빈 값은 빈 문자열");
		assertEquals("2026-11-09T08:00:00+09:00", fields.get("reportedAt"));
	}

	@Test
	void 첫_번째_등호로만_나눈다() {
		assertEquals(Map.of("message", "a=b"), MessageBody.parse("message=a=b"));
	}

	@Test
	void 빈_본문은_항목이_없다() {
		assertEquals(Map.of(), MessageBody.parse(""));
	}

	@Test
	void 형식이_틀리면_거절한다() {
		assertThrows(IllegalArgumentException.class, () -> MessageBody.parse("woId"), "등호 없음");
		assertThrows(IllegalArgumentException.class, () -> MessageBody.parse("=15"), "빈 키");
		assertThrows(IllegalArgumentException.class, () -> MessageBody.parse("woId=15|"), "빈 항목");
		assertThrows(IllegalArgumentException.class, () -> MessageBody.parse("woId=15|woId=16"), "같은 키 두 번");
	}

	@Test
	void 만든_본문을_다시_읽으면_같다() {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("resultId", 101L);
		fields.put("lotNo", null);
		fields.put("message", "작업지시 없음");
		String body = MessageBody.format(fields);

		assertEquals("resultId=101|lotNo=|message=작업지시 없음", body);
		assertEquals(Map.of("resultId", "101", "lotNo", "", "message", "작업지시 없음"), MessageBody.parse(body));
	}

	@Test
	void 구분자가_들어간_값은_만들지_않는다() {
		assertThrows(IllegalArgumentException.class, () -> MessageBody.format(Map.of("message", "a|b")));
		assertThrows(IllegalArgumentException.class, () -> MessageBody.format(Map.of("a=b", "1")));
	}

}
