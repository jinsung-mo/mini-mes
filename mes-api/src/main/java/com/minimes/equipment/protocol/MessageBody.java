package com.minimes.equipment.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 전문 본문 형식: {@code key=value|key=value}.
 * <ul>
 * <li>항목은 {@code |}로 나누고, 항목 안에서는 첫 번째 {@code =}로 키와 값을 나눈다. 값에는 {@code =}가 들어가도 된다</li>
 * <li>값에는 {@code |}를 넣을 수 없다. 빈 값({@code defectCd=})은 넣을 수 있다</li>
 * <li>키는 비어 있으면 안 되고, 같은 키가 두 번 나오면 안 된다. 어느 값을 믿을지 정할 수 없기 때문이다</li>
 * <li>빈 본문은 항목이 없는 것이다 (HBT, ACK)</li>
 * </ul>
 * 고정 길이 본문 대신 이 형식을 고른 이유는 docs/04-equipment-interface.md에 있다.
 */
public final class MessageBody {

	private MessageBody() {
	}

	/** 형식이 틀리면 {@link IllegalArgumentException} */
	public static Map<String, String> parse(String body) {
		if (body.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<String, String> fields = new LinkedHashMap<>();
		for (String item : body.split("\\|", -1)) {
			int eq = item.indexOf('=');
			if (eq <= 0) {
				throw new IllegalArgumentException("item must be key=value: '" + item + "'");
			}
			String key = item.substring(0, eq);
			if (fields.put(key, item.substring(eq + 1)) != null) {
				throw new IllegalArgumentException("duplicate key: " + key);
			}
		}
		return fields;
	}

	/** 넣은 순서대로 본문을 만든다. 값이 null이면 빈 값으로 쓴다 */
	public static String format(Map<String, ?> fields) {
		StringBuilder body = new StringBuilder();
		for (Map.Entry<String, ?> field : fields.entrySet()) {
			String key = field.getKey();
			String value = field.getValue() == null ? "" : field.getValue().toString();
			if (key.isEmpty() || key.indexOf('=') >= 0 || key.indexOf('|') >= 0) {
				throw new IllegalArgumentException("invalid key: " + key);
			}
			if (value.indexOf('|') >= 0) {
				throw new IllegalArgumentException("value must not contain '|': " + key);
			}
			if (!body.isEmpty()) {
				body.append('|');
			}
			body.append(key).append('=').append(value);
		}
		return body.toString();
	}

}
