package com.example.coffeeordersystem.global.config.dataplatform;

import com.example.coffeeordersystem.dataplatform.client.DataPlatformClient;
import com.example.coffeeordersystem.dataplatform.client.RestClientDataPlatformClient;

import java.net.http.HttpClient;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(DataPlatformProperties.class)
@ConditionalOnProperty(prefix = "data-platform.consumer", name = "enabled", havingValue = "true")
public class DataPlatformClientConfig {

	@Bean
	public DataPlatformClient dataPlatformClient(DataPlatformProperties properties) {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(properties.connectTimeout())
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.readTimeout());
		return new RestClientDataPlatformClient(RestClient.builder().requestFactory(requestFactory).build(), properties);
	}
}
