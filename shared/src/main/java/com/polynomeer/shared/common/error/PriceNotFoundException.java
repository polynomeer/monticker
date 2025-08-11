package com.polynomeer.shared.common.error;

public class PriceNotFoundException extends BaseException {
    public PriceNotFoundException(ErrorCode errorCode) {
        super(PriceErrorCode.PRICE_NOT_FOUND);
    }


}
