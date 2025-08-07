package com.polynomeer.shared.common.error;

import lombok.Getter;

import java.util.Map;

@Getter
public abstract class BaseException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Map<String, Object> metadata;

    protected BaseException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    protected BaseException(ErrorCode errorCode, Map<String, Object> metadata) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.metadata = metadata == null ? Map.of() : metadata;
    }
}
