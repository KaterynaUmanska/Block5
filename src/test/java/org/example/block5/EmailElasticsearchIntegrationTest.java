
package org.example.block5;

import org.example.block5.data.EmailData;
import org.example.block5.data.EmailStatus;
import org.example.block5.messaging.EmailMessage;
import org.example.block5.repository.EmailRepository;
import org.example.block5.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "email.scheduling.enabled=false",
        "spring.mail.host=localhost",
        "email.retry.max-attempts=0"
})
class EmailElasticsearchIntegrationTest {

    @Container
    static final ElasticsearchContainer elasticsearch =
            new ElasticsearchContainer(
                    DockerImageName.parse(
                            "docker.elastic.co/elasticsearch/elasticsearch:8.6.1"
                    )
            )
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("discovery.type", "single-node")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    @DynamicPropertySource
    static void configureProperties(
            DynamicPropertyRegistry registry
    ) {
        registry.add(
                "spring.elasticsearch.uris",
                elasticsearch::getHttpHostAddress
        );
    }

    @Autowired
    private EmailRepository emailRepository;

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    @Autowired
    private EmailService emailService;

    @MockBean
    private JavaMailSender mailSender;

    private void refreshIndex() {
        elasticsearchOperations
                .indexOps(EmailData.class)
                .refresh();
    }

    @BeforeEach
    void setUp() {
        emailRepository.deleteAll();
        refreshIndex();
        reset(mailSender);
    }

    @Test
    void shouldPersistSentEmailInRealElasticsearch() {
        emailService.sendEmail(createMessage());
        refreshIndex();

        List<EmailData> emails = emailRepository
                .findAll(PageRequest.of(0, 100))
                .getContent();

        assertEquals(1, emails.size());

        EmailData email = emails.get(0);

        assertNotNull(email.getId());
        assertEquals("test@example.com", email.getRecipient());
        assertEquals("Test subject", email.getSubject());
        assertEquals("Test content", email.getContent());
        assertEquals(EmailStatus.SENT, email.getStatus());
        assertEquals(1, email.getAttemptCount());
        assertNull(email.getErrorMessage());
        assertNotNull(email.getCreatedAt());
        assertNotNull(email.getLastAttemptAt());

        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void shouldPersistFailedEmailInRealElasticsearch() {
        doThrow(new MailSendException("SMTP unavailable"))
                .when(mailSender)
                .send(any(SimpleMailMessage.class));

        emailService.sendEmail(createMessage());
        refreshIndex();

        List<EmailData> emails = emailRepository
                .findAll(PageRequest.of(0, 100))
                .getContent();

        assertEquals(1, emails.size());

        EmailData email = emails.get(0);

        assertEquals(EmailStatus.FAILED, email.getStatus());
        assertEquals(1, email.getAttemptCount());

        assertNotNull(email.getErrorMessage());

        assertTrue(
                email.getErrorMessage()
                        .contains("SMTP unavailable")
        );

        assertTrue(
                email.getErrorMessage()
                        .contains("MailSendException")
        );
    }

    @Test
    void shouldRetryFailedEmailUsingRealElasticsearch() {
        doThrow(new MailSendException("SMTP unavailable"))
                .when(mailSender)
                .send(any(SimpleMailMessage.class));

        emailService.sendEmail(createMessage());
        refreshIndex();

        List<EmailData> failed =
                emailRepository.findAllByStatus(EmailStatus.FAILED);

        assertEquals(1, failed.size());

        String emailId = failed.get(0).getId();

        reset(mailSender);

        emailService.retryFailedEmails();

        EmailData updated = emailRepository
                .findById(emailId)
                .orElseThrow();

        assertEquals(EmailStatus.SENT, updated.getStatus());
        assertEquals(2, updated.getAttemptCount());
        assertNull(updated.getErrorMessage());

        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void shouldPersistErrorAfterFailedRetry() {
        doThrow(new MailSendException("First error"))
                .when(mailSender)
                .send(any(SimpleMailMessage.class));

        emailService.sendEmail(createMessage());
        refreshIndex();

        String id = emailRepository
                .findAllByStatus(EmailStatus.FAILED)
                .get(0)
                .getId();

        reset(mailSender);

        doThrow(new MailSendException("Second error"))
                .when(mailSender)
                .send(any(SimpleMailMessage.class));

        emailService.retryFailedEmails();

        EmailData updated = emailRepository
                .findById(id)
                .orElseThrow();

        assertEquals(EmailStatus.FAILED, updated.getStatus());
        assertEquals(2, updated.getAttemptCount());

        assertTrue(
                updated.getErrorMessage()
                        .contains("Second error")
        );
    }

    @Test
    void shouldFindEmailById() {
        emailService.sendEmail(createMessage());
        refreshIndex();

        String id = emailRepository
                .findAll(PageRequest.of(0, 100))
                .getContent()
                .get(0)
                .getId();

        EmailData email = emailService.findById(id);

        assertEquals(id, email.getId());
        assertEquals("test@example.com", email.getRecipient());
    }

    @Test
    void shouldThrowWhenEmailDoesNotExist() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> emailService.findById("nonexistent-id")
        );

        assertEquals(
                HttpStatus.NOT_FOUND,
                exception.getStatusCode()
        );
    }

    @Test
    void shouldReturnPaginatedEmailsFromRealElasticsearch() {
        emailService.sendEmail(createMessage());
        emailService.sendEmail(createMessage());
        emailService.sendEmail(createMessage());

        refreshIndex();

        Page<EmailData> firstPage = emailService.findAll(0, 2);
        Page<EmailData> secondPage = emailService.findAll(1, 2);

        assertEquals(3, firstPage.getTotalElements());
        assertEquals(2, firstPage.getTotalPages());

        assertEquals(2, firstPage.getContent().size());
        assertEquals(1, secondPage.getContent().size());

        assertEquals(0, firstPage.getNumber());
        assertEquals(1, secondPage.getNumber());
    }

    private EmailMessage createMessage() {
        return EmailMessage.builder()
                .recipient("test@example.com")
                .subject("Test subject")
                .content("Test content")
                .build();
    }
}