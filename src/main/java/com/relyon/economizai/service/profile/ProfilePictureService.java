package com.relyon.economizai.service.profile;

import com.relyon.economizai.exception.InvalidProfilePictureException;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Set;

import javax.imageio.ImageIO;

/**
 * Profile-picture lifecycle: validation, server-side downscale to a sane
 * cap (avoids storing megapixel selfies), and a deterministic initials
 * avatar fallback so the FE can render <img src="…/profile-picture"> for
 * every user without 404 handling.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfilePictureService {

    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/jpg", "image/png", "image/webp");
    private static final int MAX_DIMENSION_PX = 512;
    private static final int FALLBACK_DIMENSION_PX = 256;
    private static final String FALLBACK_CONTENT_TYPE = "image/png";
    // Hand-picked palette of brand-friendly background tones for the fallback
    // avatar. Picked deterministically by hashing the user's email so the same
    // user always gets the same color.
    private static final Color[] FALLBACK_PALETTE = new Color[]{
            new Color(0x1f6feb), new Color(0x2da44e), new Color(0xbf4b00),
            new Color(0x8957e5), new Color(0xcf222e), new Color(0xbf8700),
            new Color(0x0969da), new Color(0x6e7781)
    };

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
            var processed = downscaleIfPossible(file.getBytes(), contentType);
            String key;
            try (var stream = new ByteArrayInputStream(processed.bytes())) {
                key = storage.store(stream, processed.contentType(), processed.bytes().length);
            }
            user.setProfilePictureKey(key);
            user.setProfilePictureContentType(processed.contentType());
            user.setProfilePictureUploadedAt(LocalDateTime.now());
            userRepository.save(user);
            if (previousKey != null) {
                try { storage.delete(previousKey); } catch (Exception ignored) {}
            }
            log.info("profile_picture.upload ok user={} key={} size_in={} size_out={}",
                    LogMasker.email(user.getEmail()), key, file.getSize(), processed.bytes().length);
        } catch (IOException ex) {
            log.warn("profile_picture.upload failed user={} {}: {}",
                    LogMasker.email(user.getEmail()), ex.getClass().getSimpleName(), ex.getMessage());
            throw new InvalidProfilePictureException("error.internal");
        }
    }

    /**
     * Returns the user's stored picture, or a deterministic initials avatar
     * if they haven't uploaded one. Never throws "not found" — every user
     * has a renderable image.
     */
    @Transactional(readOnly = true)
    public ProfilePictureBytes read(User user) {
        if (user.getProfilePictureKey() != null) {
            try {
                var bytes = storage.read(user.getProfilePictureKey());
                if (bytes != null) {
                    return new ProfilePictureBytes(bytes, user.getProfilePictureContentType(), false);
                }
                log.warn("profile_picture.read.missing user={} key={} — falling back to initials",
                        LogMasker.email(user.getEmail()), user.getProfilePictureKey());
            } catch (IOException ex) {
                log.warn("profile_picture.read failed user={} {}: {} — falling back to initials",
                        LogMasker.email(user.getEmail()), ex.getClass().getSimpleName(), ex.getMessage());
            }
        }
        return new ProfilePictureBytes(generateInitialsAvatar(user), FALLBACK_CONTENT_TYPE, true);
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

    private ProcessedImage downscaleIfPossible(byte[] original, String contentType) {
        // ImageIO can't decode webp out of the box. Store as-is and trust the
        // user's source size — webp is already a small format so this is
        // mostly a no-op risk anyway.
        if ("image/webp".equalsIgnoreCase(contentType)) {
            return new ProcessedImage(original, contentType);
        }
        try {
            var source = ImageIO.read(new ByteArrayInputStream(original));
            if (source == null) {
                // Couldn't decode — pass through unchanged. The browser will
                // either render it or refuse it.
                return new ProcessedImage(original, contentType);
            }
            var maxSide = Math.max(source.getWidth(), source.getHeight());
            if (maxSide <= MAX_DIMENSION_PX) {
                return new ProcessedImage(original, contentType);
            }
            var scale = (double) MAX_DIMENSION_PX / maxSide;
            var w = (int) Math.round(source.getWidth() * scale);
            var h = (int) Math.round(source.getHeight() * scale);
            var resized = new BufferedImage(w, h,
                    "image/png".equalsIgnoreCase(contentType) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            var g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, w, h, null);
            g.dispose();
            var out = new ByteArrayOutputStream();
            var format = "image/png".equalsIgnoreCase(contentType) ? "png" : "jpeg";
            ImageIO.write(resized, format, out);
            return new ProcessedImage(out.toByteArray(), contentType);
        } catch (IOException ex) {
            log.warn("profile_picture.resize failed type={} {}: {} — storing original",
                    contentType, ex.getClass().getSimpleName(), ex.getMessage());
            return new ProcessedImage(original, contentType);
        }
    }

    private byte[] generateInitialsAvatar(User user) {
        var img = new BufferedImage(FALLBACK_DIMENSION_PX, FALLBACK_DIMENSION_PX, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        var bg = paletteFor(user);
        g.setColor(bg);
        g.fillRect(0, 0, FALLBACK_DIMENSION_PX, FALLBACK_DIMENSION_PX);

        var initials = initialsFor(user);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, FALLBACK_DIMENSION_PX / 2));
        var metrics = g.getFontMetrics();
        var x = (FALLBACK_DIMENSION_PX - metrics.stringWidth(initials)) / 2;
        var y = (FALLBACK_DIMENSION_PX - metrics.getHeight()) / 2 + metrics.getAscent();
        g.drawString(initials, x, y);
        g.dispose();

        try (var out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException ex) {
            // ByteArrayOutputStream never throws — but the API forces us to
            // declare it. Fail loud if it ever happens.
            throw new IllegalStateException("Failed to encode initials avatar", ex);
        }
    }

    private static String initialsFor(User user) {
        var name = user.getName();
        if (name == null || name.isBlank()) {
            // Fall back to email first letter when name is somehow absent.
            var email = user.getEmail();
            return (email == null || email.isBlank()) ? "?" : email.substring(0, 1).toUpperCase();
        }
        var parts = name.trim().split("\\s+");
        if (parts.length == 1) return parts[0].substring(0, 1).toUpperCase();
        return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
    }

    private static Color paletteFor(User user) {
        var seed = user.getEmail() != null ? user.getEmail() : (user.getName() != null ? user.getName() : "?");
        var idx = Math.floorMod(seed.hashCode(), FALLBACK_PALETTE.length);
        return FALLBACK_PALETTE[idx];
    }

    private record ProcessedImage(byte[] bytes, String contentType) {}

    public record ProfilePictureBytes(byte[] bytes, String contentType, boolean fallback) {}
}
