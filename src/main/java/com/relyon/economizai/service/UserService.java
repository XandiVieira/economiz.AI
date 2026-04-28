package com.relyon.economizai.service;

import com.relyon.economizai.dto.request.ChangePasswordRequest;
import com.relyon.economizai.dto.request.LoginRequest;
import com.relyon.economizai.dto.request.RegisterRequest;
import com.relyon.economizai.dto.request.UpdateUserRequest;
import com.relyon.economizai.dto.response.AuthResponse;
import com.relyon.economizai.dto.response.UserResponse;
import com.relyon.economizai.exception.EmailAlreadyExistsException;
import com.relyon.economizai.exception.InvalidCredentialsException;
import com.relyon.economizai.exception.InvalidCurrentPasswordException;
import com.relyon.economizai.model.User;
import com.relyon.economizai.repository.UserRepository;
import com.relyon.economizai.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final HouseholdService householdService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }

        var household = householdService.createSoloHousehold();
        var user = User.builder()
                .name(request.name())
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .household(household)
                .build();

        var savedUser = userRepository.save(user);
        var token = jwtService.generateToken(savedUser);
        log.info("New user registered: {} (household {})", savedUser.getEmail(), household.getId());
        return new AuthResponse(token, UserResponse.from(savedUser));
    }

    public AuthResponse login(LoginRequest request) {
        var user = userRepository.findByEmail(request.email())
                .filter(u -> passwordEncoder.matches(request.password(), u.getPassword()))
                .orElseThrow(InvalidCredentialsException::new);

        var token = jwtService.generateToken(user);
        log.info("User logged in: {}", user.getEmail());
        return new AuthResponse(token, UserResponse.from(user));
    }

    public UserResponse getProfile(User user) {
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse updateProfile(User user, UpdateUserRequest request) {
        user.setName(request.name());
        var updatedUser = userRepository.save(user);
        log.info("User profile updated: {}", updatedUser.getEmail());
        return UserResponse.from(updatedUser);
    }

    @Transactional
    public void changePassword(User user, ChangePasswordRequest request) {
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new InvalidCurrentPasswordException();
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        log.info("User {} changed password", user.getEmail());
    }
}
