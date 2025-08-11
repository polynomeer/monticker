package com.polynomeer.infra.external;

import com.polynomeer.domain.price.model.Price;
import com.polynomeer.domain.price.repository.ExternalPriceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;

@Slf4j
@Component
public class YahooFinancePriceClient implements ExternalPriceClient {

    @Override
    public Price fetchPrice(String tickerCode) {
        log.info("Fetching price from YahooFinance API for {}", tickerCode);

        return new Price(
                tickerCode,
                75000,
                -100,
                -0.13,
                12000000,
                ZonedDateTime.now()
        );
    }
}
