package com.relyon.economizai.controller;

import com.relyon.economizai.dto.response.HomeAvailabilityResponse;
import com.relyon.economizai.model.User;
import com.relyon.economizai.service.home.HomeAvailabilityService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/home")
@RequiredArgsConstructor
@Tag(name = "Home", description = "Home-screen support — cold-start feature availability for blur/lock UI")
public class HomeController {

    private final HomeAvailabilityService homeAvailabilityService;

    /**
     * Per-feature cold-start availability so the FE can show a "coming soon / building"
     * lock (with progress) on volume-gated sections instead of a bare empty state.
     */
    @GetMapping("/availability")
    public ResponseEntity<HomeAvailabilityResponse> availability(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(homeAvailabilityService.forUser(user));
    }
}
