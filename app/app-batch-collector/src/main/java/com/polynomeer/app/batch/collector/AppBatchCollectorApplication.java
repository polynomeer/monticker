package com.polynomeer.app.batch.collector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = {
        "com.polynomeer.app.batch.collector",
        "com.polynomeer.infra"
})
public class AppBatchCollectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(AppBatchCollectorApplication.class, args);
    }

}
