
package org.example.block5;

import org.example.block5.listener.EmailMessageListener;
import org.example.block5.messaging.EmailMessage;
import org.example.block5.scheduler.EmailRetryScheduler;
import org.example.block5.service.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmailInfrastructureTest {

    @Test
    void listenerShouldForwardMessageToService() {
        EmailService emailService = mock(EmailService.class);

        EmailMessageListener listener =
                new EmailMessageListener(emailService);

        EmailMessage message = EmailMessage.builder()
                .recipient("test@example.com")
                .subject("New record")
                .content("A new purchase record was created")
                .build();

        listener.onEmailMessage(message);

        verify(emailService, times(1)).sendEmail(message);
        verifyNoMoreInteractions(emailService);
    }

    @Test
    void listenerShouldUseCorrectQueue() throws Exception {
        Method method = EmailMessageListener.class.getMethod(
                "onEmailMessage",
                EmailMessage.class
        );

        RabbitListener annotation =
                method.getAnnotation(RabbitListener.class);

        assertNotNull(annotation);

        assertArrayEquals(
                new String[]{"email-service-queue"},
                annotation.queues()
        );
    }

    @Test
    void schedulerShouldCallRetryService() {
        EmailService emailService = mock(EmailService.class);

        EmailRetryScheduler scheduler =
                new EmailRetryScheduler(emailService);

        scheduler.retryFailedEmails();

        verify(emailService, times(1))
                .retryFailedEmails();

        verifyNoMoreInteractions(emailService);
    }

    @Test
    void schedulerShouldHaveFiveMinuteDelay() throws Exception {
        Method method = EmailRetryScheduler.class.getMethod(
                "retryFailedEmails"
        );

        Scheduled annotation =
                method.getAnnotation(Scheduled.class);

        assertNotNull(annotation);

        assertEquals(
                "${email.retry.delay:300000}",
                annotation.fixedDelayString()
        );
    }
}
