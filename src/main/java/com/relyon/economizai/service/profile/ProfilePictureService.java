package com.relyon.economizai.service.profile;

import com.relyon.economizai.exception.InvalidProfilePictureException;
import com.relyon.economizai.exception.ProfilePictureNotFoundException;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfilePictureService {

    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/jpg", "image/png", "image/webp");

    private final ProfilePictureStorage storage;
    private final UserRepository userRepository;

    @Value("${economizai.profile-picture.max-size-mb:5}")
    private int maxSizeMb;

    @Transactional
    public void upload(User user, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidProfilePictureException("profile.picture.empty");
        }
        var contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
            throw new InvalidProfilePictureException("profile.picture.invalid.type");
        }
        var maxBytes = (long) maxSizeMb * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new InvalidProfilePictureException("profile.picture.too.large", String.valueOf(maxSizeMb));
        }

        var previousKey = user.getProfilePictureKey();
        try {
            var key = storage.store(file.getInputStream(), contentType, file.getSize());
            user.setProfilePictureKey(key);
            user.setProfilePictureContentType(contentType);
            user.setProfilePictureUploadedAt(LocalDateTime.now());
            userRepository.save(user);
            if (previousKey != null) {
                // Best-effort cleanup of the orphaned old file. If it fails we log
                // and move on — won't break the upload.
                try { storage.delete(previousKey); } catch (Exception ignored) {}
            }
            log.info("profile_picture.upload ok user={} key={} size={}",
                    LogMasker.email(user.getEmail()), key, file.getSize());
        } catch (IOException ex) {
            log.warn("profile_picture.upload failed user={} {}: {}",
                    LogMasker.email(user.getEmail()), ex.getClass().getSimpleName(), ex.getMessage());
            throw new InvalidProfilePictureException("error.internal");
        }
    }

    @Transactional(readOnly = true)
    public ProfilePictureBytes read(User user) {
        if (user.getProfilePictureKey() == null) {
            throw new ProfilePictureNotFoundException();
        }
        try {
            var bytes = storage.read(user.getProfilePictureKey());
            if (bytes == null) throw new ProfilePictureNotFoundException();
            return new ProfilePictureBytes(bytes, user.getProfilePictureContentType());
        } catch (IOException ex) {
            log.warn("profile_picture.read failed user={} {}: {}",
                    LogMasker.email(user.getEmail()), ex.getClass().getSimpleName(), ex.getMessage());
            throw new ProfilePictureNotFoundException();
        }
    }

    @Transactional
    public void delete(User user) {
        var key = user.getProfilePictureKey();
        if (key == null) return;
        user.setProfilePictureKey(null);
        user.setProfilePictureContentType(null);
        user.setProfilePictureUploadedAt(null);
        userRepository.save(user);
        try { storage.delete(key); } catch (Exception ignored) {}
        log.info("profile_picture.deleted user={}", LogMasker.email(user.getEmail()));
    }

    public record ProfilePictureBytes(byte[] bytes, String contentType) {}
}
