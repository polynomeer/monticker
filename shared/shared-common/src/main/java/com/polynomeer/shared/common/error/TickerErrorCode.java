package com.polynomeer.shared.common.error;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@AllArgsConstructor
public enum TickerErrorCode implements ErrorCode {
    TICKER_NOT_FOUND("TICKER-001", "해당 종목을 찾을 수 없습니다.", HttpStatus.NOT_FOUND);

    @Getter
    private final String code;
    @Getter
    private final String message;
    private final HttpStatus status;

    @Override
    public HttpStatus getHttpStatus() {
        return status;
    }
}
