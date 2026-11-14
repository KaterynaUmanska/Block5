
package org.example.block5.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.block5.data.EmailData;
import org.example.block5.data.EmailStatus;
import org.example.block5.messaging.EmailMessage;
import org.example.block5.repository.EmailRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int RETRY_BATCH_SIZE = 100;

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    );

    private final EmailRepository emailRepository;
    private final JavaMailSender mailSender;

    @Value("${email.retry.max-attempts:0}")
    private int maxAttempts;

    public void sendEmail(EmailMessage message) {
        validate(message);

        EmailData email = EmailData.builder()
                .recipient(message.getRecipient().trim())
                .subject(message.getSubject())
                .content(message.getContent())
                .status(EmailStatus.FAILED)
                .attemptCount(0)
                .createdAt(Instant.now())
                .build();

        email = emailRepository.save(email);

        processEmail(email);
    }

    private void validate(EmailMessage message) {
        if (message == null
                || !StringUtils.hasText(message.getRecipient())
                || !StringUtils.hasText(message.getSubject())
                || !StringUtils.hasText(message.getContent())) {

            throw new IllegalArgumentException(
                    "Email message must contain recipient, subject and content"
            );
        }

        String recipient = message.getRecipient().trim();

        if (!EMAIL_PATTERN.matcher(recipient).matches()) {
            throw new IllegalArgumentException(
                    "Invalid recipient email address: " + recipient
            );
        }
    }

    private void processEmail(EmailData email) {
        email.setAttemptCount(email.getAttemptCount() + 1);
        email.setLastAttemptAt(Instant.now());

        try {
            SimpleMailMessage mail = new SimpleMailMessage();

            mail.setTo(email.getRecipient());
            mail.setSubject(email.getSubject());
            mail.setText(email.getContent());

            mailSender.send(mail);

            email.setStatus(EmailStatus.SENT);
            email.setErrorMessage(null);

            log.info(
                    "Email successfully sent to {}",
                    email.getRecipient()
            );

        } catch (Exception exception) {
            email.setStatus(EmailStatus.FAILED);

            email.setErrorMessage(
                    exception.getClass().getName()
                            + ": "
                            + exception.getMessage()
            );

            log.error(
                    "Failed to send email to {}",
                    email.getRecipient(),
                    exception
            );
        }

        emailRepository.save(email);
    }

    public void retryFailedEmails() {
        List<String> failedIds = new ArrayList<>();

        int pageNumber = 0;
        Page<EmailData> page;

        do {
            page = emailRepository.findAllByStatus(
                    EmailStatus.FAILED,
                    PageRequest.of(pageNumber, RETRY_BATCH_SIZE)
            );

            for (EmailData email : page.getContent()) {
                if (canRetry(email)) {
                    failedIds.add(email.getId());
                }
            }

            pageNumber++;
        } while (page.hasNext());

        log.info(
                "Found {} failed emails eligible for retry",
                failedIds.size()
        );

        for (String id : failedIds) {
            try {
                emailRepository.findById(id).ifPresent(email -> {
                    if (email.getStatus() == EmailStatus.FAILED
                            && canRetry(email)) {
                        processEmail(email);
                    }
                });
            } catch (Exception exception) {
                log.error(
                        "Could not process retry for email id {}",
                        id,
                        exception
                );
            }
        }
    }

    private boolean canRetry(EmailData email) {
        return maxAttempts <= 0
                || email.getAttemptCount() < maxAttempts;
    }

    public Page<EmailData> findAll(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Page must be >= 0 and size must be between 1 and 100"
            );
        }

        Pageable pageable = PageRequest.of(page, size);

        return emailRepository.findAll(pageable);
    }

    public EmailData findById(String id) {
        return emailRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Email not found: " + id
                ));
    }
}
