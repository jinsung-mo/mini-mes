package com.minimes;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** 설비 게이트웨이는 빈 포트(0)로 연다. 개발 서버가 9101을 쓰고 있어도 테스트가 실패하지 않게 */
@SpringBootTest(properties = "mes.equipment.port=0")
class MesApiApplicationTests {

	@Test
	void contextLoads() {
	}

}
