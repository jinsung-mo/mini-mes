package com.minimes.production;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 근무조와 근무일자. A 06~14, B 14~22, C 22~06이고, 00~06시는 C조이면서 전날 근무일자다.
 * <p>
 * 어느 시간대의 시각을 넣을지는 호출하는 쪽이 정한다. 지금은 레거시처럼 서버 시각을 넣는다
 * (레거시 문제점 2. 5단계에서 공장 시간대 시각으로 바꾼다).
 */
public record WorkShift(LocalDate workDate, String shiftCd) {

	public static WorkShift of(LocalDateTime at) {
		int hour = at.getHour();
		LocalDate date = at.toLocalDate();
		if (hour >= 6 && hour < 14) {
			return new WorkShift(date, "A");
		}
		if (hour >= 14 && hour < 22) {
			return new WorkShift(date, "B");
		}
		return new WorkShift(hour < 6 ? date.minusDays(1) : date, "C");
	}

}
