package com.relyon.economizai.model;

import com.relyon.economizai.model.enums.AuthProvider;
import com.relyon.economizai.model.enums.Role;
import com.relyon.economizai.model.enums.SubscriptionTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class User extends BaseEntity implements UserDetails {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    // Nullable: social-login (Google/Apple) users have no local password.
    @Column(nullable = true)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false, length = 20)
    @Builder.Default
    private AuthProvider authProvider = AuthProvider.LOCAL;

    // Stable provider-issued subject id (Google/Apple "sub"). Null for LOCAL.
    @Column(name = "provider_subject", length = 255)
    private String providerSubject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Role role = Role.USER;

    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_tier", nullable = false)
    @Builder.Default
    private SubscriptionTier subscriptionTier = SubscriptionTier.FREE;

    @Column(name = "contribution_opt_in", nullable = false)
    @Builder.Default
    private boolean contributionOptIn = true;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    // Java `transient` (NOT JPA @Transient — the column stays mapped): User is
    // Serializable via UserDetails, but Household is not, so a serialization of
    // the principal would throw NotSerializableException (Sonar S1948). Sessions
    // are STATELESS (JWT) so the principal is never serialized today; marking it
    // transient keeps that safe and future-proof (a lazy @ManyToOne reloads from
    // the DB anyway if it ever were deserialized).
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id", nullable = false)
    private transient Household household;

    @Column(name = "accepted_terms_version", nullable = false, length = 20)
    private String acceptedTermsVersion;

    @Column(name = "accepted_privacy_version", nullable = false, length = 20)
    private String acceptedPrivacyVersion;

    @Column(name = "accepted_legal_at", nullable = false)
    private LocalDateTime acceptedLegalAt;

    @Column(name = "home_latitude", precision = 10, scale = 7)
    private BigDecimal homeLatitude;

    @Column(name = "home_longitude", precision = 10, scale = 7)
    private BigDecimal homeLongitude;

    @Column(name = "home_set_at")
    private LocalDateTime homeSetAt;

    @Column(name = "push_device_token", length = 500)
    private String pushDeviceToken;

    @Column(name = "push_token_updated_at")
    private LocalDateTime pushTokenUpdatedAt;

    @Column(name = "profile_picture_key", length = 255)
    private String profilePictureKey;

    @Column(name = "profile_picture_content_type", length = 50)
    private String profilePictureContentType;

    @Column(name = "profile_picture_uploaded_at")
    private LocalDateTime profilePictureUploadedAt;

    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return active;
    }

    @Override
    public boolean isAccountNonLocked() {
        return active;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return active;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
