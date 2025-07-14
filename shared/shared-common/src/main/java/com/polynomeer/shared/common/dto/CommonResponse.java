package com.polynomeer.shared.common.dto;

import lombok.Getter;

@Getter
public class CommonResponse<T> {
    private final String code;
    private final T data;
    private final String message;

    public CommonResponse(String code, T data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }
    public CommonResponse(String code, T data) {
        this(code, data, null);
    }
}
