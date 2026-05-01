package com.relyon.economizai.controller;

import com.relyon.economizai.dto.response.HouseholdPreferenceResponse;
import com.relyon.economizai.model.User;
import com.relyon.economizai.service.preferences.HouseholdPreferenceService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/preferences")
@RequiredArgsConstructor
@Tag(name = "Preferences", description = "Auto-derived household pack-size and brand preferences (Phase 2.6)")
public class PreferenceController {

    private final HouseholdPreferenceService preferenceService;

    @GetMapping
    public ResponseEntity<List<HouseholdPreferenceResponse>> myPreferences(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(preferenceService.derivePreferences(user));
    }
}
