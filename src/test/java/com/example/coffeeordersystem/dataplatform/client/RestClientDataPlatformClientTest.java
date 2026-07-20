package com.example.coffeeordersystem.dataplatform.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

import com.example.coffeeordersystem.global.config.dataplatform.DataPlatformProperties;

import java.time.Duration;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientDataPlatformClientTest {

	@Test
	void 주문완료_이벤트를_고정_멱등키와_함께_전송한다() {
		// given
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RestClientDataPlatformClient client = new RestClientDataPlatformClient(
			builder.build(), new DataPlatformProperties("http://data-platform.test/orders", Duration.ofSeconds(1), Duration.ofSeconds(1))
		);
		DataPlatformOrderPaidRequest request = new DataPlatformOrderPaidRequest(42L, 3L, 7L, 4_500);
		server.expect(requestTo("http://data-platform.test/orders"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Idempotency-Key", "order-paid:42"))
			.andExpect(content().json("{\"eventId\":42,\"userId\":3,\"menuId\":7,\"paymentAmount\":4500}"))
			.andRespond(withNoContent());

		// when
		client.send(request);

		// then
		server.verify();
	}

	@Test
	void 오백대_응답은_재시도_가능한_실패로_분류한다() {
		// given
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RestClientDataPlatformClient client = new RestClientDataPlatformClient(
			builder.build(), new DataPlatformProperties("http://data-platform.test/orders", Duration.ofSeconds(1), Duration.ofSeconds(1))
		);
		server.expect(requestTo("http://data-platform.test/orders")).andRespond(withServerError());

		// when
		var action = (org.assertj.core.api.ThrowableAssert.ThrowingCallable) () ->
			client.send(new DataPlatformOrderPaidRequest(42L, 3L, 7L, 4_500));

		// then
		assertThatThrownBy(action).isInstanceOf(RetryableDataPlatformException.class);
	}

	@Test
	void 연결_실패는_재시도_가능한_실패로_분류한다() {
		// given
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		RestClientDataPlatformClient client = new RestClientDataPlatformClient(
			builder.build(), new DataPlatformProperties("http://data-platform.test/orders", Duration.ofSeconds(1), Duration.ofSeconds(1))
		);
		server.expect(requestTo("http://data-platform.test/orders")).andRespond(request -> {
			throw new IOException("connection refused");
		});

		// when
		var action = (org.assertj.core.api.ThrowableAssert.ThrowingCallable) () ->
			client.send(new DataPlatformOrderPaidRequest(42L, 3L, 7L, 4_500));

		// then
		assertThatThrownBy(action).isInstanceOf(RetryableDataPlatformException.class);
	}
}
