package org.example.block5.scheduler;

import org.example.block5.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailRetryScheduler {

    private final EmailService emailService;

    @Scheduled(fixedDelayString = "${email.retry.delay:300000}")
    public void retryFailedEmails() {
        log.info("Starting failed email retry task");

        emailService.retryFailedEmails();
    }

}
