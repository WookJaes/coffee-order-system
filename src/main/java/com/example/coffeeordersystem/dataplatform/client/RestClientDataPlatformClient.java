package com.example.coffeeordersystem.dataplatform.client;

import com.example.coffeeordersystem.global.config.dataplatform.DataPlatformProperties;

import lombok.RequiredArgsConstructor;

import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@RequiredArgsConstructor
public class RestClientDataPlatformClient implements DataPlatformClient {

	private final RestClient restClient;
	private final DataPlatformProperties properties;

	@Override
	public void send(DataPlatformOrderPaidRequest request) {
		try {
			restClient.post()
				.uri(properties.apiUrl())
				.header("Idempotency-Key", request.idempotencyKey())
				.body(request)
				.retrieve()
				.toBodilessEntity();
		} catch (HttpClientErrorException exception) {
			throw new NonRetryableDataPlatformException("데이터 플랫폼이 복구 불가능한 응답을 반환했습니다.", exception);
		} catch (RestClientException exception) {
			throw new RetryableDataPlatformException("데이터 플랫폼 전달에 일시적으로 실패했습니다.", exception);
		}
	}
}
