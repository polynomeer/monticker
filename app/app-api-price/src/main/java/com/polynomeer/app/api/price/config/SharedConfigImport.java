package com.polynomeer.app.api.price.config;

import com.polynomeer.shared.config.global.GlobalExceptionHandler;
import com.polynomeer.shared.config.logging.TraceIdFilter;
import com.polynomeer.shared.config.web.SwaggerConfig;
import com.polynomeer.shared.config.web.WebMvcConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({TraceIdFilter.class, GlobalExceptionHandler.class, SwaggerConfig.class, WebMvcConfig.class})
public class SharedConfigImport {
}
