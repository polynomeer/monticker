package com.polynomeer.app.api.price;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {
        "com.polynomeer.app.api.price",
        "com.polynomeer.domain.price",
        "com.polynomeer.infra",
})
public class AppApiPriceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AppApiPriceApplication.class, args);
    }

}
