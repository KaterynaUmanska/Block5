package org.example.block5.service;

import org.example.block5.data.EmailData;
import org.example.block5.data.EmailStatus;
import org.example.block5.messaging.EmailMessage;
import org.example.block5.repository.EmailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private static final int MAX_PAGE_SIZE = 100;

    private final EmailRepository emailRepository;
    private final JavaMailSender mailSender;

    /**
     * Saves the incoming email before attempting delivery.
     */
    public void sendEmail(EmailMessage message) {
        validate(message);

        EmailData email = EmailData.builder()
                .recipient(message.getRecipient().trim())
                .subject(message.getSubject())
                .content(message.getContent())
                .status(EmailStatus.PENDING)
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
                || message.getContent() == null) {

            throw new IllegalArgumentException(
                    "Email message must contain recipient, subject and content"
            );
        }
    }

    /**
     * Performs one email delivery attempt.
     */
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

    /**
     * Retries all emails with FAILED status.
     */
    public void retryFailedEmails() {
        List<EmailData> failedEmails =
                emailRepository.findAllByStatus(EmailStatus.FAILED);

        log.info(
                "Found {} failed emails to retry",
                failedEmails.size()
        );

        for (EmailData email : failedEmails) {
            try {
                processEmail(email);
            } catch (Exception exception) {
                log.error(
                        "Could not process retry for email id {}",
                        email.getId(),
                        exception
                );
            }
        }
    }

    public List<EmailData> findAll() {
        return emailRepository
                .findAll(PageRequest.of(0, MAX_PAGE_SIZE))
                .getContent();
    }

    public EmailData findById(String id) {
        return emailRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Email not found: " + id
                ));
    }
}