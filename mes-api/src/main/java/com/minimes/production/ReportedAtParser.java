package com.minimes.production;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * 단말이 보낸 보고 시각을 레거시(C# {@code DateTime.TryParse})와 같은 규칙으로 서버 로컬 시각으로 바꾼다.
 * <ul>
 * <li>오프셋이 있으면 ("2026-10-29T08:00:00-05:00"): 서버 시간대로 바꾼다. 한국 서버면 22:00이 된다.
 * 근무조가 틀리게 계산되는 원인이지만, 옮기는 단계라 그대로 둔다 (레거시 문제점 2)</li>
 * <li>오프셋이 없으면 ("2026-10-29T08:00"): 서버 시각으로 본다</li>
 * <li>날짜만 있으면 ("2026-10-29"): 그날 00:00</li>
 * </ul>
 * TryParse는 "2026/10/29 8:00" 같은 형식도 받지만, 단말 화면이 보내는 ISO 형식과 그 변형만 맞췄다.
 * 그 밖의 형식은 E001이 된다.
 */
final class ReportedAtParser {

	private ReportedAtParser() {
	}

	/** 해석할 수 없으면 null */
	static LocalDateTime toServerLocal(String value, ZoneId serverZone) {
		String text = value.trim();
		// TryParse는 날짜와 시각 사이의 공백도 받는다
		if (text.length() > 10 && text.charAt(10) == ' ') {
			text = text.substring(0, 10) + 'T' + text.substring(11);
		}
		try {
			return OffsetDateTime.parse(text).atZoneSameInstant(serverZone).toLocalDateTime();
		}
		catch (DateTimeParseException e) {
			// 오프셋 없는 형식으로 다시 시도
		}
		try {
			return LocalDateTime.parse(text);
		}
		catch (DateTimeParseException e) {
			// 날짜만 있는 형식으로 다시 시도
		}
		try {
			return LocalDate.parse(text).atStartOfDay();
		}
		catch (DateTimeParseException e) {
			return null;
		}
	}

}
