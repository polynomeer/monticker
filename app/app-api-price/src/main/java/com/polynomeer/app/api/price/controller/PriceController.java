package com.polynomeer.app.api.price.controller;

import com.polynomeer.app.api.price.dto.PriceResponse;
import com.polynomeer.domain.price.service.PriceQueryService;
import com.polynomeer.domain.ticker.validation.TickerFormat;
import com.polynomeer.shared.common.dto.CommonResponse;
import com.polynomeer.shared.common.error.TickerErrorCode;
import com.polynomeer.shared.common.error.TickerValidationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Quotes", description = "Operations related to stock quotes")
public class PriceController {

    private final PriceQueryService priceQueryService;

    @Operation(summary = "Get quote by ticker code", description = "Returns stock quote data for a given ticker code.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successful response"),
            @ApiResponse(responseCode = "404", description = "Ticker not found")
    })
    @GetMapping("/{tickerCode}")
    public CommonResponse<PriceResponse> getQuote(
            @Parameter(description = "Ticker code of the stock", example = "AAPL")
            @PathVariable String tickerCode
    ) {
        log.info("getQuote tickerCode={}", tickerCode);

        validateTickerCode(tickerCode);

        var price = priceQueryService.getCurrentPrice(tickerCode);
        var response = PriceResponse.from(price);
        return new CommonResponse<>("SUCCESS", response);
    }

    private void validateTickerCode(String tickerCode) {
        if (!TickerFormat.isValid(tickerCode)) {
            throw new TickerValidationException(TickerErrorCode.TICKER_INVALID);
        }
    }

}
