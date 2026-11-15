
package org.example.block5.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.block5.service.EmailService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailRetryScheduler {

    private final EmailService emailService;

    @Scheduled(fixedDelayString = "${email.retry.delay:300000}")
    public void retryFailedEmails() {
        log.info("Starting email retry and recovery task");

        emailService.retryFailedEmails();
        emailService.recoverPendingEmails();

        log.info("Email retry and recovery task completed");
    }
}
