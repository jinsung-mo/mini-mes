package com.minimes.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class ReportedAtParserTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static LocalDateTime parse(String value) {
		return ReportedAtParser.toServerLocal(value, SEOUL);
	}

	@Test
	void 오프셋이_있으면_서버_시간대로_바꾼다() {
		// 레거시 문제점 2를 그대로 옮긴 동작: 앨라배마 08:00 → 한국 서버 22:00 → C조
		LocalDateTime local = parse("2026-10-29T08:00:00-05:00");
		assertEquals(LocalDateTime.of(2026, 10, 29, 22, 0), local);
		assertEquals("C", WorkShift.of(local).shiftCd());

		assertEquals(LocalDateTime.of(2026, 10, 29, 9, 0), parse("2026-10-29T00:00:00Z"));
	}

	@Test
	void 오프셋이_없으면_서버_시각으로_본다() {
		assertEquals(LocalDateTime.of(2026, 10, 29, 8, 0), parse("2026-10-29T08:00"));
		assertEquals(LocalDateTime.of(2026, 10, 29, 8, 0, 30), parse("2026-10-29 08:00:30"));
	}

	@Test
	void 날짜만_있으면_자정이다() {
		assertEquals(LocalDateTime.of(2026, 10, 29, 0, 0), parse("2026-10-29"));
	}

	@Test
	void 해석할_수_없으면_null() {
		assertNull(parse("abc"));
		assertNull(parse("2026-02-30T08:00"));
		assertNull(parse("2026-10-29T25:00"));
	}

}
