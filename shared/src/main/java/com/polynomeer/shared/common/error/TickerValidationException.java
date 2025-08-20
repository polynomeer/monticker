package com.polynomeer.shared.common.error;

public class TickerValidationException extends BaseException {
    public TickerValidationException(ErrorCode errorCode) {
        super(TickerErrorCode.TICKER_INVALID);
    }
}
