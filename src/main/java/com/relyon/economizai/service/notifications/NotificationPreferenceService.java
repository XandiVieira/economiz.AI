package com.relyon.economizai.service.notifications;

import com.relyon.economizai.dto.request.UpdateNotificationPreferencesRequest;
import com.relyon.economizai.dto.request.UpdatePushTokenRequest;
import com.relyon.economizai.dto.response.NotificationPreferenceResponse;
import com.relyon.economizai.model.NotificationPreference;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.NotificationPreferenceRepository;
import com.relyon.economizai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<NotificationPreferenceResponse> list(User user) {
        return preferenceRepository.findAllByUserId(user.getId()).stream()
                .map(p -> new NotificationPreferenceResponse(p.getType(), p.getChannel()))
                .toList();
    }

    @Transactional
    public List<NotificationPreferenceResponse> update(User user, UpdateNotificationPreferencesRequest request) {
        for (var entry : request.preferences()) {
            var existing = preferenceRepository.findByUserIdAndType(user.getId(), entry.type());
            var preference = existing.orElseGet(() -> NotificationPreference.builder()
                    .user(user).type(entry.type()).build());
            preference.setChannel(entry.channel());
            preferenceRepository.save(preference);
        }
        log.info("notification.preferences.updated user={} count={}", user.getEmail(), request.preferences().size());
        return list(user);
    }

    @Transactional
    public void updatePushToken(User user, UpdatePushTokenRequest request) {
        var token = request.pushDeviceToken();
        user.setPushDeviceToken(token == null || token.isBlank() ? null : token);
        user.setPushTokenUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("push_token.updated user={} hasToken={}", user.getEmail(), user.getPushDeviceToken() != null);
    }
}
