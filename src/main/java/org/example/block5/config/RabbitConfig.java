
package org.example.block5.config;

import org.example.block5.messaging.EmailMessage;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JavaTypeMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class RabbitConfig {

    public static final String QUEUE_EMAIL =
            "email-service-queue";

    public static final String EXCHANGE_EMAIL =
            "email-created-exchange";

    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
        Jackson2JsonMessageConverter converter =
                new Jackson2JsonMessageConverter();

        DefaultJackson2JavaTypeMapper typeMapper =
                new DefaultJackson2JavaTypeMapper();

        typeMapper.setTypePrecedence(
                Jackson2JavaTypeMapper.TypePrecedence.INFERRED
        );

        typeMapper.setTrustedPackages(
                "org.example.block2.messaging",
                "org.example.block5.messaging"
        );

        typeMapper.setIdClassMapping(
                Map.of(
                        "org.example.block2.messaging.EmailMessage",
                        EmailMessage.class
                )
        );

        converter.setJavaTypeMapper(typeMapper);

        return converter;
    }

    @Bean
    public Queue emailQueue() {
        return new Queue(QUEUE_EMAIL, true);
    }

    @Bean
    public FanoutExchange emailExchange() {
        return new FanoutExchange(
                EXCHANGE_EMAIL,
                true,
                false
        );
    }

    @Bean
    public Binding emailBinding() {
        return BindingBuilder
                .bind(emailQueue())
                .to(emailExchange());
    }
}
