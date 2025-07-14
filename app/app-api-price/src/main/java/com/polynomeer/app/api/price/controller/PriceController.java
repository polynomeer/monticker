package com.polynomeer.app.api.price.controller;

import com.polynomeer.app.api.price.Price;
import com.polynomeer.app.api.price.dto.PriceResponse;
import com.polynomeer.shared.common.dto.CommonResponse;
import com.polynomeer.shared.common.error.TickerErrorCode;
import com.polynomeer.shared.common.error.TickerNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quotes")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Quotes", description = "Operations related to stock quotes")
public class PriceController {

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

        if (tickerCode.equals("error")) {
            throw new TickerNotFoundException(TickerErrorCode.TICKER_NOT_FOUND);
        }

        return new CommonResponse<>(
                "SUCCESS",
                PriceResponse.from(
                        new Price("TEST", 1000L, 10L, 100.0, 10000L, null)
                ));
    }
}
