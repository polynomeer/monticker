package com.polynomeer.shared.common.error;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@AllArgsConstructor
@Getter
public enum PriceErrorCode implements ErrorCode {
    PRICE_NOT_FOUND("PRICE-001", "해당 종목의 가격을 찾을 수 없습니다.", HttpStatus.NOT_FOUND);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
