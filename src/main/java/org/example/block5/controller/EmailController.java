
package org.example.block5.controller;

import lombok.RequiredArgsConstructor;
import org.example.block5.data.EmailData;
import org.example.block5.service.EmailService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/emails")
@RequiredArgsConstructor
public class EmailController {

    private final EmailService emailService;

    @GetMapping
    public Page<EmailData> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return emailService.findAll(page, size);
    }

    @GetMapping("/{id}")
    public EmailData findById(@PathVariable String id) {
        return emailService.findById(id);
    }
}
