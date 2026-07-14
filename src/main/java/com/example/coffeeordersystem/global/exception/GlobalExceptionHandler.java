package com.example.coffeeordersystem.global.exception;

import com.example.coffeeordersystem.global.response.ErrorResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.validation.FieldError;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException exception) {
		ErrorCode errorCode = exception.getErrorCode();
		return ResponseEntity.status(errorCode.getStatus())
			.body(ErrorResponse.of(errorCode.getStatus(), errorCode.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException exception) {
		ErrorCode errorCode = exception.getFieldError("userId") != null
			? ErrorCode.INVALID_USER_ID
			: ErrorCode.INVALID_CHARGE_AMOUNT;
		FieldError fieldError = exception.getBindingResult().getFieldError();
		String message = fieldError != null ? fieldError.getDefaultMessage() : errorCode.getMessage();
		return ResponseEntity.status(errorCode.getStatus())
			.body(ErrorResponse.of(errorCode.getStatus(), message));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException() {
		ErrorCode errorCode = ErrorCode.INVALID_CHARGE_AMOUNT;
		return ResponseEntity.status(errorCode.getStatus())
			.body(ErrorResponse.of(errorCode.getStatus(), errorCode.getMessage()));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleException() {
		ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
		return ResponseEntity.status(errorCode.getStatus())
			.body(ErrorResponse.of(errorCode.getStatus(), errorCode.getMessage()));
	}
}
