package com.polynomeer.shared.common.error;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@AllArgsConstructor
public enum TickerErrorCode implements ErrorCode {
    TICKER_NOT_FOUND("TICKER-001", "해당 종목을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    TICKER_INVALID("TICKER-002", "요청된 Ticker가 유효하지 않습니다.", HttpStatus.BAD_REQUEST);

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
