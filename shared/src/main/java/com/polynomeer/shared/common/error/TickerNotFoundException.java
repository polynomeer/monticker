package com.polynomeer.shared.common.error;

public class TickerNotFoundException extends BaseException {
    public TickerNotFoundException(ErrorCode errorCode) {
        super(TickerErrorCode.TICKER_NOT_FOUND);
    }
}
