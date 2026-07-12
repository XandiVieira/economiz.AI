package com.relyon.economizai.controller;

import com.relyon.economizai.dto.request.BetaSignupRequest;
import com.relyon.economizai.service.ContactService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/beta-signup")
@RequiredArgsConstructor
@Tag(name = "Beta signup", description = "Public beta-tester lead capture — emails the interested user to our beta inbox")
public class BetaSignupController {

    private final ContactService contactService;

    /** Public (no auth), IP rate-limited. Emails a beta-tester lead to our inbox. */
    @PostMapping
    public ResponseEntity<Void> submit(@Valid @RequestBody BetaSignupRequest request) {
        contactService.submitBetaSignup(request);
        return ResponseEntity.accepted().build();
    }
}
