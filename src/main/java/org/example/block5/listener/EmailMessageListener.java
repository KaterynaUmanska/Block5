package org.example.block5.listener;

import org.example.block5.messaging.EmailMessage;
import org.example.block5.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import static org.example.block5.config.RabbitConfig.QUEUE_EMAIL;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailMessageListener {

    private final EmailService emailService;

    @RabbitListener(queues = QUEUE_EMAIL)
    public void onEmailMessage(EmailMessage message) {
        log.info("Received email message for {}", message.getRecipient());

        emailService.sendEmail(message);
    }

}
