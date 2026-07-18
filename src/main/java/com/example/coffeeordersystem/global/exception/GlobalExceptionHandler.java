package com.example.coffeeordersystem.global.exception;

import com.example.coffeeordersystem.global.response.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

	private static final String INVALID_REQUEST_BODY_MESSAGE = "요청 본문 형식이 올바르지 않습니다.";
	private static final String METHOD_NOT_ALLOWED_MESSAGE = "지원하지 않는 HTTP 메서드입니다.";
	private static final String UNSUPPORTED_MEDIA_TYPE_MESSAGE = "지원하지 않는 Content-Type입니다.";

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException exception) {
		ErrorCode errorCode = exception.getErrorCode();
		return ResponseEntity.status(errorCode.getStatus())
			.body(ErrorResponse.of(errorCode.getStatus(), errorCode.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException exception) {
		ErrorCode errorCode = getValidationErrorCode(exception);
		FieldError fieldError = exception.getBindingResult().getFieldError();
		String message = fieldError != null ? fieldError.getDefaultMessage() : errorCode.getMessage();
		return ResponseEntity.status(errorCode.getStatus())
			.body(ErrorResponse.of(errorCode.getStatus(), message));
	}

	private ErrorCode getValidationErrorCode(MethodArgumentNotValidException exception) {
		if (exception.getFieldError("userId") != null) {
			return ErrorCode.INVALID_USER_ID;
		}
		if (exception.getFieldError("menuId") != null) {
			return ErrorCode.INVALID_MENU_ID;
		}
		if (exception.getFieldError("quantity") != null) {
			return ErrorCode.INVALID_ORDER_QUANTITY;
		}
		return ErrorCode.INVALID_CHARGE_AMOUNT;
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException() {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.body(ErrorResponse.of(HttpStatus.BAD_REQUEST, INVALID_REQUEST_BODY_MESSAGE));
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ErrorResponse> handleDataIntegrityViolationException(DataIntegrityViolationException exception) {
		ErrorCode errorCode = isIdempotencyKeyConflict(exception)
			? ErrorCode.IDEMPOTENCY_KEY_CONFLICT
			: ErrorCode.INTERNAL_SERVER_ERROR;
		return ResponseEntity.status(errorCode.getStatus())
			.body(ErrorResponse.of(errorCode.getStatus(), errorCode.getMessage()));
	}

	private boolean isIdempotencyKeyConflict(DataIntegrityViolationException exception) {
		Throwable cause = exception;
		while (cause != null) {
			String message = cause.getMessage();
			if (message != null && message.contains("uk_orders_user_id_idempotency_key")) {
				return true;
			}
			cause = cause.getCause();
		}
		return false;
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleHttpRequestMethodNotSupportedException(
		HttpRequestMethodNotSupportedException exception
	) {
		return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
			.body(ErrorResponse.of(HttpStatus.METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED_MESSAGE));
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleHttpMediaTypeNotSupportedException(HttpMediaTypeNotSupportedException exception) {
		return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
			.body(ErrorResponse.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED_MEDIA_TYPE_MESSAGE));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleException(Exception exception) {
		log.error("Unexpected exception occurred", exception);
		ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
		return ResponseEntity.status(errorCode.getStatus())
			.body(ErrorResponse.of(errorCode.getStatus(), errorCode.getMessage()));
	}
}
