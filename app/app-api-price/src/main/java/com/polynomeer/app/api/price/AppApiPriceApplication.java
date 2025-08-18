package com.polynomeer.app.api.price;

import com.polynomeer.domain.price.repository.PriceCacheProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {
        "com.polynomeer.app.api.price",
        "com.polynomeer.domain.price",
        "com.polynomeer.infra",
})
@EnableConfigurationProperties(PriceCacheProperties.class)
public class AppApiPriceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AppApiPriceApplication.class, args);
    }

}
