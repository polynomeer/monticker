package com.polynomeer.shared.common.error;

import org.springframework.http.HttpStatus;

public interface ErrorCode {
    String getCode();

    String getMessage();

    HttpStatus getHttpStatus();
}
