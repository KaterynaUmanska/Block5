package org.example.block5.controller;

import org.example.block5.data.EmailData;
import org.example.block5.service.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/emails")
@RequiredArgsConstructor
public class EmailController {

    private final EmailService emailService;

    @GetMapping
    public List<EmailData> findAll() {
        return emailService.findAll();
    }

    @GetMapping("/{id}")
    public EmailData findById(@PathVariable String id) {
        return emailService.findById(id);
    }

}
