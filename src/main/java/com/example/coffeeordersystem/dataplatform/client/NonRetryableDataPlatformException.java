package com.example.coffeeordersystem.dataplatform.client;

public class NonRetryableDataPlatformException extends RuntimeException {

	public NonRetryableDataPlatformException(String message, Throwable cause) {
		super(message, cause);
	}
}
