package com.minimes.production;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class WorkShiftTest {

	private static WorkShift at(String dateTime) {
		return WorkShift.of(LocalDateTime.parse(dateTime));
	}

	@Test
	void 경계_시각은_새_근무조에_속한다() {
		assertEquals(new WorkShift(LocalDate.of(2026, 10, 29), "A"), at("2026-10-29T06:00"));
		assertEquals(new WorkShift(LocalDate.of(2026, 10, 29), "B"), at("2026-10-29T14:00"));
		assertEquals(new WorkShift(LocalDate.of(2026, 10, 29), "C"), at("2026-10-29T22:00"));
	}

	@Test
	void 경계_직전은_이전_근무조다() {
		assertEquals("C", at("2026-10-29T05:59:59").shiftCd());
		assertEquals("A", at("2026-10-29T13:59:59").shiftCd());
		assertEquals("B", at("2026-10-29T21:59:59").shiftCd());
	}

	@Test
	void 자정부터_6시까지는_전날_근무일자의_C조다() {
		assertEquals(new WorkShift(LocalDate.of(2026, 10, 28), "C"), at("2026-10-29T00:00"));
		assertEquals(new WorkShift(LocalDate.of(2026, 10, 28), "C"), at("2026-10-29T05:59"));
		// 월·연 경계
		assertEquals(new WorkShift(LocalDate.of(2026, 12, 31), "C"), at("2027-01-01T03:00"));
	}

	@Test
	void 자정_전_C조는_당일_근무일자다() {
		assertEquals(new WorkShift(LocalDate.of(2026, 10, 29), "C"), at("2026-10-29T23:59"));
	}

}
