package com.polynomeer.shared.config.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Romanticker API")
                        .description("시세, 관심 종목 등 Romanticker의 주요 기능을 제공하는 API 문서")
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("Polynomeer")
                                .email("polynomeer@gmail.com")
                                .url("https://romanticker.io"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://springdoc.org")));
    }
}
