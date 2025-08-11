package com.polynomeer.infra.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polynomeer.domain.price.model.Price;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Price> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Price> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

        Jackson2JsonRedisSerializer<Price> serializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, Price.class);

        template.setValueSerializer(serializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);

        template.afterPropertiesSet();
        return template;
    }
}


