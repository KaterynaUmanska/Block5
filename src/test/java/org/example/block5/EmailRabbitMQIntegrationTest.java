
package org.example.block5;

import org.example.block5.messaging.EmailMessage;
import org.example.block5.service.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;

import static org.example.block5.config.RabbitConfig.EXCHANGE_EMAIL;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(properties = {
        "email.scheduling.enabled=false",
        "spring.elasticsearch.uris=http://localhost:9200",
        "spring.data.elasticsearch.repositories.enabled=false"
})
class EmailRabbitMQIntegrationTest {

    @Container
    static final RabbitMQContainer rabbitMQ =
            new RabbitMQContainer(
                    DockerImageName.parse(
                            "rabbitmq:3.13-management"
                    )
            );

    @DynamicPropertySource
    static void configureProperties(
            DynamicPropertyRegistry registry
    ) {
        registry.add(
                "spring.rabbitmq.host",
                rabbitMQ::getHost
        );

        registry.add(
                "spring.rabbitmq.port",
                rabbitMQ::getAmqpPort
        );

        registry.add(
                "spring.rabbitmq.username",
                rabbitMQ::getAdminUsername
        );

        registry.add(
                "spring.rabbitmq.password",
                rabbitMQ::getAdminPassword
        );
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @MockBean
    private EmailService emailService;

    @Test
    void shouldReceiveMessageFromRabbitMQ() {
        EmailMessage message = EmailMessage.builder()
                .recipient("admin@example.com")
                .subject("New purchase record")
                .content("Purchase record created")
                .source("PurchaseRecord")
                .sourceId(123L)
                .build();

        rabbitTemplate.convertAndSend(
                EXCHANGE_EMAIL,
                "",
                message
        );

        verify(emailService, timeout(5000).times(1))
                .sendEmail(any(EmailMessage.class));
    }

    @Test
    void shouldDeserializeBlock2Message() {
        String json = """
                {
                  "recipient": "admin@example.com",
                  "subject": "New purchase record",
                  "content": "Purchase record 123 created",
                  "source": "PurchaseRecord",
                  "sourceId": 123
                }
                """;

        MessageProperties properties =
                new MessageProperties();

        properties.setContentType(
                MessageProperties.CONTENT_TYPE_JSON
        );

        properties.setHeader(
                "__TypeId__",
                "org.example.block2.messaging.EmailMessage"
        );

        Message rabbitMessage = new Message(
                json.getBytes(StandardCharsets.UTF_8),
                properties
        );

        rabbitTemplate.send(
                EXCHANGE_EMAIL,
                "",
                rabbitMessage
        );

        verify(emailService, timeout(5000).times(1))
                .sendEmail(
                        argThat(message ->
                                "admin@example.com".equals(
                                        message.getRecipient()
                                )
                                        && "New purchase record".equals(
                                        message.getSubject()
                                )
                                        && "Purchase record 123 created".equals(
                                        message.getContent()
                                )
                                        && "PurchaseRecord".equals(
                                        message.getSource()
                                )
                                        && Long.valueOf(123L).equals(
                                        message.getSourceId()
                                )
                        )
                );
    }
}
