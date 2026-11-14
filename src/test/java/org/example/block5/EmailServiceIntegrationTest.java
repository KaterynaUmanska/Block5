
package org.example.block5;

import org.example.block5.data.EmailData;
import org.example.block5.data.EmailStatus;
import org.example.block5.messaging.EmailMessage;
import org.example.block5.repository.EmailRepository;
import org.example.block5.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailServiceIntegrationTest {

    @Mock
    private EmailRepository emailRepository;

    @Mock
    private JavaMailSender mailSender;

    private EmailService emailService;

    private List<EmailData> savedSnapshots;

    @BeforeEach
    void setUp() {
        emailService = new EmailService(
                emailRepository,
                mailSender
        );

        savedSnapshots = new ArrayList<>();

        lenient().when(emailRepository.save(any(EmailData.class)))
                .thenAnswer(invocation -> {
                    EmailData email = invocation.getArgument(0);

                    savedSnapshots.add(copy(email));

                    if (email.getId() == null) {
                        email.setId("generated-id");
                    }

                    return email;
                });
    }

    @Test
    void shouldSavePendingBeforeSending() {
        emailService.sendEmail(createMessage());

        assertEquals(2, savedSnapshots.size());

        EmailData initial = savedSnapshots.get(0);

        assertEquals(EmailStatus.PENDING, initial.getStatus());
        assertEquals(0, initial.getAttemptCount());
        assertNull(initial.getErrorMessage());
        assertNull(initial.getLastAttemptAt());
        assertNotNull(initial.getCreatedAt());
    }

    @Test
    void shouldSendCorrectEmailContent() {
        emailService.sendEmail(createMessage());

        ArgumentCaptor<SimpleMailMessage> captor =
                ArgumentCaptor.forClass(SimpleMailMessage.class);

        verify(mailSender).send(captor.capture());

        SimpleMailMessage mail = captor.getValue();

        assertArrayEquals(
                new String[]{"test@example.com"},
                mail.getTo()
        );

        assertEquals("Test subject", mail.getSubject());
        assertEquals("Test content", mail.getText());
    }

    @Test
    void shouldTrimRecipientBeforeSavingAndSending() {
        EmailMessage message = EmailMessage.builder()
                .recipient("  test@example.com  ")
                .subject("Test subject")
                .content("Test content")
                .build();

        emailService.sendEmail(message);

        assertEquals(
                "test@example.com",
                savedSnapshots.get(0).getRecipient()
        );

        ArgumentCaptor<SimpleMailMessage> captor =
                ArgumentCaptor.forClass(SimpleMailMessage.class);

        verify(mailSender).send(captor.capture());

        assertArrayEquals(
                new String[]{"test@example.com"},
                captor.getValue().getTo()
        );
    }

    @Test
    void shouldSetSentStatusAfterSuccessfulSending() {
        emailService.sendEmail(createMessage());

        assertEquals(2, savedSnapshots.size());

        EmailData result = savedSnapshots.get(1);

        assertEquals(EmailStatus.SENT, result.getStatus());
        assertEquals(1, result.getAttemptCount());
        assertNull(result.getErrorMessage());
        assertNotNull(result.getLastAttemptAt());

        verify(mailSender, times(1))
                .send(any(SimpleMailMessage.class));
    }

    @Test
    void shouldSetFailedStatusAndErrorMessage() {
        doThrow(new MailSendException("SMTP unavailable"))
                .when(mailSender)
                .send(any(SimpleMailMessage.class));

        emailService.sendEmail(createMessage());

        EmailData result = savedSnapshots.get(1);

        assertEquals(EmailStatus.FAILED, result.getStatus());
        assertEquals(1, result.getAttemptCount());

        assertTrue(
                result.getErrorMessage()
                        .contains("MailSendException")
        );

        assertTrue(
                result.getErrorMessage()
                        .contains("SMTP unavailable")
        );

        assertNotNull(result.getLastAttemptAt());
    }

    @Test
    void shouldRetryFailedEmailSuccessfully() {
        EmailData failed = createFailedEmail();

        Instant previousAttempt = failed.getLastAttemptAt();

        when(emailRepository.findAllByStatus(EmailStatus.FAILED))
                .thenReturn(List.of(failed));

        emailService.retryFailedEmails();

        assertEquals(EmailStatus.SENT, failed.getStatus());
        assertEquals(2, failed.getAttemptCount());
        assertNull(failed.getErrorMessage());

        assertTrue(
                failed.getLastAttemptAt().isAfter(previousAttempt)
        );

        verify(mailSender).send(any(SimpleMailMessage.class));
        verify(emailRepository).save(failed);
    }

    @Test
    void shouldUpdateErrorAfterFailedRetry() {
        EmailData failed = createFailedEmail();

        when(emailRepository.findAllByStatus(EmailStatus.FAILED))
                .thenReturn(List.of(failed));

        doThrow(new MailSendException("Still unavailable"))
                .when(mailSender)
                .send(any(SimpleMailMessage.class));

        emailService.retryFailedEmails();

        assertEquals(EmailStatus.FAILED, failed.getStatus());
        assertEquals(2, failed.getAttemptCount());

        assertTrue(
                failed.getErrorMessage()
                        .contains("Still unavailable")
        );

        assertNotNull(failed.getLastAttemptAt());
    }

    @Test
    void shouldProcessMultipleFailedEmails() {
        EmailData first = createFailedEmail();
        first.setId("first");

        EmailData second = createFailedEmail();
        second.setId("second");

        when(emailRepository.findAllByStatus(EmailStatus.FAILED))
                .thenReturn(List.of(first, second));

        emailService.retryFailedEmails();

        assertEquals(EmailStatus.SENT, first.getStatus());
        assertEquals(EmailStatus.SENT, second.getStatus());

        assertEquals(2, first.getAttemptCount());
        assertEquals(2, second.getAttemptCount());

        verify(mailSender, times(2))
                .send(any(SimpleMailMessage.class));
    }

    @Test
    void shouldKeepProcessingAfterOneEmailFails() {
        EmailData first = createFailedEmail();
        first.setId("first");

        EmailData second = createFailedEmail();
        second.setId("second");

        when(emailRepository.findAllByStatus(EmailStatus.FAILED))
                .thenReturn(List.of(first, second));

        doThrow(new MailSendException("First failed"))
                .doNothing()
                .when(mailSender)
                .send(any(SimpleMailMessage.class));

        emailService.retryFailedEmails();

        assertEquals(EmailStatus.FAILED, first.getStatus());
        assertEquals(EmailStatus.SENT, second.getStatus());

        assertEquals(2, first.getAttemptCount());
        assertEquals(2, second.getAttemptCount());
    }

    @Test
    void shouldDoNothingWhenNoFailedEmailsExist() {
        when(emailRepository.findAllByStatus(EmailStatus.FAILED))
                .thenReturn(List.of());

        emailService.retryFailedEmails();

        verifyNoInteractions(mailSender);
        verify(emailRepository, never())
                .save(any(EmailData.class));
    }

    @Test
    void shouldRejectNullMessage() {
        assertThrows(
                IllegalArgumentException.class,
                () -> emailService.sendEmail(null)
        );

        verifyNoInteractions(mailSender);
        verifyNoInteractions(emailRepository);
    }

    @Test
    void shouldRejectBlankRecipient() {
        EmailMessage message = EmailMessage.builder()
                .recipient("   ")
                .subject("Subject")
                .content("Content")
                .build();

        assertThrows(
                IllegalArgumentException.class,
                () -> emailService.sendEmail(message)
        );

        verifyNoInteractions(mailSender);
        verifyNoInteractions(emailRepository);
    }

    @Test
    void shouldRejectBlankSubject() {
        EmailMessage message = EmailMessage.builder()
                .recipient("test@example.com")
                .subject("  ")
                .content("Content")
                .build();

        assertThrows(
                IllegalArgumentException.class,
                () -> emailService.sendEmail(message)
        );

        verifyNoInteractions(mailSender);
        verifyNoInteractions(emailRepository);
    }

    @Test
    void shouldRejectNullContent() {
        EmailMessage message = EmailMessage.builder()
                .recipient("test@example.com")
                .subject("Subject")
                .content(null)
                .build();

        assertThrows(
                IllegalArgumentException.class,
                () -> emailService.sendEmail(message)
        );

        verifyNoInteractions(mailSender);
        verifyNoInteractions(emailRepository);
    }

    @Test
    void shouldNotSendIfInitialSaveFails() {
        when(emailRepository.save(any(EmailData.class)))
                .thenThrow(new RuntimeException("Elasticsearch unavailable"));

        assertThrows(
                RuntimeException.class,
                () -> emailService.sendEmail(createMessage())
        );

        verifyNoInteractions(mailSender);
    }

    private EmailMessage createMessage() {
        return EmailMessage.builder()
                .recipient("test@example.com")
                .subject("Test subject")
                .content("Test content")
                .build();
    }

    private EmailData createFailedEmail() {
        return EmailData.builder()
                .id("failed-id")
                .recipient("test@example.com")
                .subject("Test subject")
                .content("Test content")
                .status(EmailStatus.FAILED)
                .errorMessage("Previous error")
                .attemptCount(1)
                .createdAt(Instant.now().minusSeconds(600))
                .lastAttemptAt(Instant.now().minusSeconds(300))
                .build();
    }

    private EmailData copy(EmailData email) {
        return EmailData.builder()
                .id(email.getId())
                .recipient(email.getRecipient())
                .subject(email.getSubject())
                .content(email.getContent())
                .status(email.getStatus())
                .errorMessage(email.getErrorMessage())
                .attemptCount(email.getAttemptCount())
                .createdAt(email.getCreatedAt())
                .lastAttemptAt(email.getLastAttemptAt())
                .build();
    }
}
