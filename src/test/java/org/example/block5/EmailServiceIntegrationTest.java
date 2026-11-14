
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

        ReflectionTestUtils.setField(
                emailService,
                "maxAttempts",
                0
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

        mockFailedEmails(failed);

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

        mockFailedEmails(failed);

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

        mockFailedEmails(first, second);

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

        mockFailedEmails(first, second);

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
        when(emailRepository.findAllByStatus(
                eq(EmailStatus.FAILED),
                any(Pageable.class)
        )).thenReturn(Page.empty());

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
    void shouldRejectInvalidEmailAddress() {
        EmailMessage message = EmailMessage.builder()
                .recipient("invalid-email")
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
                .subject("   ")
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
    void shouldRejectBlankContent() {
        EmailMessage message = EmailMessage.builder()
                .recipient("test@example.com")
                .subject("Subject")
                .content("   ")
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
                .thenThrow(
                        new RuntimeException("Elasticsearch unavailable")
                );

        assertThrows(
                RuntimeException.class,
                () -> emailService.sendEmail(createMessage())
        );

        verifyNoInteractions(mailSender);
    }

    @Test
    void shouldStopRetryingAfterMaximumAttempts() {
        ReflectionTestUtils.setField(
                emailService,
                "maxAttempts",
                2
        );

        EmailData failed = createFailedEmail();
        failed.setAttemptCount(2);

        when(emailRepository.findAllByStatus(
                eq(EmailStatus.FAILED),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(failed)));

        emailService.retryFailedEmails();

        assertEquals(EmailStatus.FAILED, failed.getStatus());
        assertEquals(2, failed.getAttemptCount());

        verifyNoInteractions(mailSender);

        verify(emailRepository, never())
                .save(any(EmailData.class));
    }

    @Test
    void shouldRetryWhenBelowMaximumAttempts() {
        ReflectionTestUtils.setField(
                emailService,
                "maxAttempts",
                2
        );

        EmailData failed = createFailedEmail();

        mockFailedEmails(failed);

        emailService.retryFailedEmails();

        assertEquals(EmailStatus.SENT, failed.getStatus());
        assertEquals(2, failed.getAttemptCount());

        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void shouldRetryWithoutLimitByDefault() {
        EmailData failed = createFailedEmail();
        failed.setAttemptCount(100);

        mockFailedEmails(failed);

        emailService.retryFailedEmails();

        assertEquals(EmailStatus.SENT, failed.getStatus());
        assertEquals(101, failed.getAttemptCount());

        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void shouldReturnPaginatedEmails() {
        EmailData email = createFailedEmail();

        Page<EmailData> expected = new PageImpl<>(
                List.of(email),
                PageRequest.of(1, 20),
                21
        );

        when(emailRepository.findAll(PageRequest.of(1, 20)))
                .thenReturn(expected);

        Page<EmailData> result = emailService.findAll(1, 20);

        assertEquals(1, result.getContent().size());
        assertEquals(21, result.getTotalElements());
        assertEquals(1, result.getNumber());
        assertEquals(20, result.getSize());

        verify(emailRepository)
                .findAll(PageRequest.of(1, 20));
    }

    @Test
    void shouldRejectInvalidPagination() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> emailService.findAll(-1, 20)
        );

        assertEquals(
                HttpStatus.BAD_REQUEST,
                exception.getStatusCode()
        );

        assertThrows(
                ResponseStatusException.class,
                () -> emailService.findAll(0, 0)
        );

        assertThrows(
                ResponseStatusException.class,
                () -> emailService.findAll(0, 101)
        );

        verifyNoInteractions(emailRepository);
    }

    @Test
    void shouldReturnNotFoundForMissingEmail() {
        when(emailRepository.findById("missing-id"))
                .thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> emailService.findById("missing-id")
        );

        assertEquals(
                HttpStatus.NOT_FOUND,
                exception.getStatusCode()
        );
    }

    private void mockFailedEmails(EmailData... emails) {
        when(emailRepository.findAllByStatus(
                eq(EmailStatus.FAILED),
                any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(emails)));

        for (EmailData email : emails) {
            when(emailRepository.findById(email.getId()))
                    .thenReturn(Optional.of(email));
        }
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