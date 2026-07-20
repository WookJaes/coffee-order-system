package com.example.coffeeordersystem.dataplatform.client;

public class RetryableDataPlatformException extends RuntimeException {

	public RetryableDataPlatformException(String message, Throwable cause) {
		super(message, cause);
	}
}
