package com.minimes.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.minimes.master.MasterController;
import com.minimes.master.MasterService;
import com.minimes.workorder.CreateWorkOrderRequest;
import com.minimes.workorder.CreatedWorkOrder;
import com.minimes.workorder.WorkOrderController;
import com.minimes.workorder.WorkOrderService;

/**
 * 요청을 레거시(ASP.NET)와 같은 규칙으로 해석하는지 DB 없이 확인한다.
 * C#과 직접 비교하는 일은 scripts/equivalence.py가 하고, 여기서는 그 비교로 찾아 맞춘 설정이 빠지지 않았는지 본다.
 */
@WebMvcTest({ MasterController.class, WorkOrderController.class })
class LegacyRequestConfigTest {

	private static final String CREATE = "{\"plantCd\": %s, \"itemCd\": %s, \"planQty\": %s, \"planDate\": \"2026-11-03\"}";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private MasterService masterService;

	@MockitoBean
	private WorkOrderService workOrderService;

	@Test
	void 주소의_대소문자와_끝의_슬래시를_가리지_않는다() throws Exception {
		mvc.perform(get("/API/Plants/")).andExpect(status().isOk()).andExpect(jsonPath("$.resultCode").value("0000"));
		mvc.perform(post("/api/work-orders/1/release/")).andExpect(status().isOk());
	}

	@Test
	void 쿼리_파라미터_이름의_대소문자를_가리지_않고_같은_이름이면_첫_값을_쓴다() throws Exception {
		mvc.perform(get("/api/items?PLANTCD=KR01&plantCd=US01")).andExpect(status().isOk());
		verify(masterService).getItems("KR01");
	}

	@Test
	void 문자열_칸에_숫자나_true가_오면_400() throws Exception {
		for (String json : List.of(CREATE.formatted("1", "\"SEDAN-A\"", "1"), CREATE.formatted("\"KR01\"", "true", "1"),
				CREATE.formatted("\"KR01\"", "1.5", "1"))) {
			mvc.perform(post("/api/work-orders").contentType(MediaType.APPLICATION_JSON).content(json))
					.andExpect(status().isBadRequest());
		}
		verify(workOrderService, never()).create(any());
	}

	@Test
	void 숫자_칸의_문자열은_레거시처럼_받는다() throws Exception {
		given(workOrderService.create(any())).willReturn(new CreatedWorkOrder(1, "WO-KR01-20261103-001"));
		mvc.perform(post("/api/work-orders").contentType(MediaType.APPLICATION_JSON)
				.content(CREATE.formatted("\"KR01\"", "\"SEDAN-A\"", "\"4\"")))
				.andExpect(status().isOk());

		ArgumentCaptor<CreateWorkOrderRequest> request = ArgumentCaptor.forClass(CreateWorkOrderRequest.class);
		verify(workOrderService).create(request.capture());
		assertEquals(4, request.getValue().getPlanQty());
	}

	@Test
	void 본문도_Content_Type도_없으면_415() throws Exception {
		mvc.perform(post("/api/work-orders")).andExpect(status().isUnsupportedMediaType());
	}

	@Test
	void Content_Type이_있는데_본문이_비었으면_400() throws Exception {
		mvc.perform(post("/api/work-orders").contentType(MediaType.APPLICATION_JSON).content(""))
				.andExpect(status().isBadRequest());
	}

}
