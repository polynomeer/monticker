package com.polynomeer.app.api.price.controller;

import com.polynomeer.app.api.price.Price;
import com.polynomeer.app.api.price.dto.PriceResponse;
import com.polynomeer.shared.common.dto.ApiResponse;
import com.polynomeer.shared.common.error.BaseException;
import com.polynomeer.shared.common.error.TickerErrorCode;
import com.polynomeer.shared.common.error.TickerNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/quotes")
@RequiredArgsConstructor
@Slf4j
public class PriceController {

    @GetMapping("/{tickerCode}")
    public ApiResponse<PriceResponse> getQuote(@PathVariable String tickerCode) {
        log.info("getQuote tickerCode={}", tickerCode);

        if (tickerCode.equals("error")) {
            throw new TickerNotFoundException(TickerErrorCode.TICKER_NOT_FOUND);
        }

        return new ApiResponse<>(
                "SUCCESS",
                PriceResponse.from(
                        new Price("TEST", 1000L, 10L, 100.0, 10000L, null)
                ));
    }
}
