package com.relyon.economizai.service.profile;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Dev-only profile-picture storage. Writes bytes to a directory on local disk
 * — fine for single-instance dev / Render free tier, BROKEN for prod (Render
 * free tier disk is wiped on every redeploy; multi-instance setups can't
 * share a local FS).
 *
 * <p>See DEV_NOTES.md for the prod migration plan (S3 / Cloudinary / Render
 * persistent disk).
 */
@Slf4j
@Component
public class LocalDiskProfilePictureStorage implements ProfilePictureStorage {

    private final Path baseDir;

    public LocalDiskProfilePictureStorage(
            @Value("${economizai.profile-picture.local-dir:/tmp/economizai/profile-pics}") String dir) {
        this.baseDir = Paths.get(dir);
    }

    @PostConstruct
    void ensureDir() throws IOException {
        Files.createDirectories(baseDir);
        log.info("profile_picture.storage.local_disk dir={}", baseDir);
    }

    @Override
    public String store(InputStream bytes, String contentType, long sizeBytes) throws IOException {
        var key = UUID.randomUUID().toString() + extensionFor(contentType);
        var target = baseDir.resolve(key);
        Files.copy(bytes, target);
        log.info("profile_picture.stored key={} size={}", key, sizeBytes);
        return key;
    }

    @Override
    public byte[] read(String key) throws IOException {
        var path = baseDir.resolve(key);
        if (!Files.exists(path)) return null;
        return Files.readAllBytes(path);
    }

    @Override
    public void delete(String key) throws IOException {
        if (key == null) return;
        Files.deleteIfExists(baseDir.resolve(key));
    }

    private String extensionFor(String contentType) {
        if (contentType == null) return "";
        return switch (contentType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> "";
        };
    }
}
