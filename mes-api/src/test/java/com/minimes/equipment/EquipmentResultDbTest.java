package com.minimes.equipment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.minimes.TestDatabase;
import com.minimes.equipment.protocol.Frame;
import com.minimes.equipment.protocol.MessageBody;
import com.minimes.workorder.CreateWorkOrderRequest;
import com.minimes.workorder.WorkOrderService;

/**
 * 설비 전문이 실제 DB에 어떻게 반영되는지 소켓부터 끝까지 확인한다.
 * 테스트용 DB(mes_equipment_test)를 새로 만들어 쓰므로 개발 DB의 작업지시는 건드리지 않는다.
 * DB 컨테이너가 필요해서 기본 test에서는 빠지고 {@code ./gradlew dbTest}로 실행한다.
 */
@Tag("db")
@SpringBootTest(properties = "mes.equipment.port=0")
class EquipmentResultDbTest {

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		String url = TestDatabase.recreate("mes_equipment_test");
		registry.add("spring.datasource.url", () -> url);
	}

	@Autowired
	private EquipmentGateway gateway;

	@Autowired
	private WorkOrderService workOrderService;

	@Autowired
	private JdbcTemplate jdbc;

	/** 진행 중인 작업지시를 만든다 */
	private long startedWorkOrder(int planQty) {
		CreateWorkOrderRequest request = new CreateWorkOrderRequest();
		request.setPlantCd("CZ01");
		request.setItemCd("SEDAN-A");
		request.setPlanQty(planQty);
		request.setPlanDate("2026-11-09");
		long woId = workOrderService.create(request).woId();
		workOrderService.release(woId);
		workOrderService.start(woId);
		return woId;
	}

	private static Frame result(String messageId, long woId, String eqpCd, int goodQty) {
		String procCd = eqpCd.split("-")[1];
		return new Frame(Frame.RESULT, messageId,
				"woId=" + woId + "|procCd=" + procCd + "|eqpCd=" + eqpCd + "|goodQty=" + goodQty + "|defectQty=0");
	}

	private int resultCount(long woId) {
		return jdbc.queryForObject("SELECT count(*) FROM production_result WHERE wo_id = ?", Integer.class, woId);
	}

	private int goodQty(long woId) {
		return jdbc.queryForObject("SELECT good_qty FROM work_order WHERE wo_id = ?", Integer.class, woId);
	}

	private static Map<String, String> body(Frame frame) {
		return MessageBody.parse(frame.body());
	}

	@Test
	void 실적을_반영하고_REST와_같은_항목으로_ACK한다() throws Exception {
		long woId = startedWorkOrder(10);
		try (EquipmentClient client = new EquipmentClient(gateway.getPort())) {
			Frame ack = client.request(result("M1", woId, "EQ-BODY-01", 3));

			assertEquals(Frame.ACK, ack.type(), ack.body());
			assertEquals("M1", ack.messageId());
			Map<String, String> fields = body(ack);
			assertEquals(List.of("result_id", "lot_no", "work_date", "shift_cd", "wo_good_qty", "wo_status"),
					List.copyOf(fields.keySet()));
			assertTrue(fields.get("lot_no").startsWith("L-CZ01-"), fields.get("lot_no"));
			assertEquals("3", fields.get("wo_good_qty"));
			assertEquals("IN_PROGRESS", fields.get("wo_status"));
		}
		assertEquals(1, resultCount(woId));
	}

	@Test
	void 같은_메시지_ID를_다시_보내면_반영하지_않고_처음_ACK를_돌려준다() throws Exception {
		long woId = startedWorkOrder(10);
		try (EquipmentClient client = new EquipmentClient(gateway.getPort())) {
			Frame first = client.request(result("M1", woId, "EQ-BODY-01", 3));
			Frame again = client.request(result("M1", woId, "EQ-BODY-01", 3));
			assertEquals(first, again);
		}
		assertEquals(1, resultCount(woId));
		assertEquals(3, goodQty(woId));
	}

	@Test
	void 완료된_뒤의_재전송도_E006이_아니라_처음_ACK를_받는다() throws Exception {
		long woId = startedWorkOrder(2);
		try (EquipmentClient client = new EquipmentClient(gateway.getPort())) {
			Frame first = client.request(result("M1", woId, "EQ-BODY-01", 2));
			assertEquals("COMPLETED", body(first).get("wo_status"));

			assertEquals(first, client.request(result("M1", woId, "EQ-BODY-01", 2)), "재전송");

			Frame newResult = client.request(result("M2", woId, "EQ-BODY-01", 1));
			assertEquals(Frame.NAK, newResult.type());
			assertEquals("E006", body(newResult).get("code"), "새 실적은 REST처럼 E006");
		}
		assertEquals(1, resultCount(woId));
	}

	@Test
	void 같은_메시지_ID로_다른_내용을_보내면_반영하지_않는다() throws Exception {
		long woId = startedWorkOrder(10);
		try (EquipmentClient client = new EquipmentClient(gateway.getPort())) {
			client.request(result("M1", woId, "EQ-BODY-01", 3));
			Frame reused = client.request(result("M1", woId, "EQ-BODY-01", 5));
			assertEquals(Frame.NAK, reused.type());
			assertEquals("E001", body(reused).get("code"));
		}
		assertEquals(1, resultCount(woId));
	}

	@Test
	void 메시지_ID가_같아도_설비가_다르면_다른_실적이다() throws Exception {
		long woId = startedWorkOrder(10);
		try (EquipmentClient client = new EquipmentClient(gateway.getPort())) {
			assertEquals(Frame.ACK, client.request(result("M1", woId, "EQ-BODY-01", 1)).type());
			assertEquals(Frame.ACK, client.request(result("M1", woId, "EQ-PAINT-01", 1)).type());
		}
		assertEquals(2, resultCount(woId));
	}

	@Test
	void 연결_여러_개로_같은_전문을_동시에_보내도_한_번만_반영한다() throws Exception {
		// ACK를 못 받은 설비가 새 연결로 다시 보내는 경우를 몰아서 재현한다
		long woId = startedWorkOrder(100);
		int senders = 10;
		CountDownLatch ready = new CountDownLatch(senders);
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(senders);
		List<Future<Frame>> replies = new ArrayList<>();
		for (int i = 0; i < senders; i++) {
			Callable<Frame> send = () -> {
				try (EquipmentClient client = new EquipmentClient(gateway.getPort())) {
					ready.countDown();
					go.await();
					return client.request(result("DUP-1", woId, "EQ-BODY-01", 4));
				}
			};
			replies.add(pool.submit(send));
		}
		ready.await();
		go.countDown();

		Frame first = replies.get(0).get();
		assertEquals(Frame.ACK, first.type(), first.body());
		for (Future<Frame> reply : replies) {
			assertEquals(first, reply.get(), "모두 같은 ACK (같은 LOT 번호)");
		}
		pool.shutdown();

		assertEquals(1, resultCount(woId));
		assertEquals(4, goodQty(woId));
	}

	@Test
	void 검증_순서와_에러_코드는_REST와_같다() throws Exception {
		long woId = startedWorkOrder(10);
		try (EquipmentClient client = new EquipmentClient(gateway.getPort())) {
			assertEquals("E001", nakCode(client, "procCd=BODY|eqpCd=EQ-BODY-01|goodQty=1"));
			assertEquals("E002", nakCode(client, "woId=" + woId + "|procCd=BODY|eqpCd=EQ-BODY-01"));
			assertEquals("E004", nakCode(client, "woId=99999999|procCd=BODY|eqpCd=EQ-BODY-01|goodQty=1"));
			assertEquals("E009", nakCode(client, "woId=" + woId + "|procCd=BODY|eqpCd=EQ-PAINT-01|goodQty=1"));
			assertEquals("E007", nakCode(client, "woId=" + woId + "|procCd=BODY|eqpCd=EQ-BODY-01|defectQty=1"));
			assertEquals("E001", nakCode(client, "woId=" + woId + "|goodQty=1.5"));
			assertEquals("E001", nakCode(client, "woId"));
		}
		assertEquals(0, resultCount(woId));
		assertEquals(0, (int) jdbc.queryForObject("SELECT count(*) FROM equipment_message WHERE wo_id = ?",
				Integer.class, woId), "NAK로 끝난 전문은 기록하지 않는다");
	}

	private int nakNo;

	private String nakCode(EquipmentClient client, String body) throws Exception {
		Frame reply = client.request(new Frame(Frame.RESULT, "N" + (++nakNo), body));
		assertEquals(Frame.NAK, reply.type(), body);
		return body(reply).get("code");
	}

}
