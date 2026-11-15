package org.angel.paymentclean.application.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.angel.paymentclean.domain.enums.Role;
import org.angel.paymentclean.domain.exception.ArticleBusinessConflictException;
import org.angel.paymentclean.domain.exception.InvalidProfileUpdateException;
import org.angel.paymentclean.domain.exception.UserNotFoundException;
import org.angel.paymentclean.domain.model.TokenRefreshResult;
import org.angel.paymentclean.domain.model.User;
import org.angel.paymentclean.domain.model.UsernameUpdateResult;
import org.angel.paymentclean.domain.port.in.AuthUserUseCase;
import org.angel.paymentclean.domain.port.out.StripeCustomerPort;
import org.angel.paymentclean.domain.port.out.UserRepositoryPort;
import org.angel.paymentclean.domain.util.IdGenerator;
import org.angel.paymentclean.infrastructure.security.JwtTokenException;
import org.angel.paymentclean.infrastructure.security.JwtUtil;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class AuthUserUseCaseImpl implements AuthUserUseCase {

    private final UserRepositoryPort userRepository;
    private final StripeCustomerPort stripeCustomerPort;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthUserUseCaseImpl(UserRepositoryPort userRepository, StripeCustomerPort stripeCustomerPort, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.stripeCustomerPort = stripeCustomerPort;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    @Override
    public String register(String username, String email, String password, String phone) {
        return register(username, email, password, phone, null);
    }

    @Override
    public String register(String username, String email, String password, String phone, String role) {
        String passwordEncode = passwordEncoder.encode(password);
        Role assignedRole = resolveRole(role);

        User user = new User(
                IdGenerator.userId(),
                username,
                email,
                passwordEncode,
                phone,
                stripeCustomerPort.crearClienteEnStripe(username, email, phone),
                assignedRole
        );

        userRepository.save(user);

        return "Usuario creado exitosamente";
    }

    @Override
    public String login(String username, String password) {
        User user = userRepository.findByUsername(username).orElseThrow(UserNotFoundException::new);

        if (passwordEncoder.matches(password, user.password())) {
            return jwtUtil.generateToken(username, user.role());
        } else {
            throw new BadCredentialsException("Invalid password");
        }
    }

    @Override
    public User getCurrentUser(String username) {
        if (username == null || username.isBlank()) {
            throw new BadCredentialsException("No autenticado");
        }
        return userRepository.findByUsername(username).orElseThrow(UserNotFoundException::new);
    }

    @Override
    public TokenRefreshResult refreshToken(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtTokenException("INVALID_TOKEN", "Token JWT requerido.");
        }

        Claims claims;
        try {
            claims = jwtUtil.parseValidClaims(token);
        } catch (ExpiredJwtException ex) {
            throw new JwtTokenException("TOKEN_EXPIRED", "El token JWT ha expirado.", ex);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new JwtTokenException("INVALID_TOKEN", "Token JWT inválido.", ex);
        }

        String username = claims.getSubject();
        if (username == null || username.isBlank()) {
            throw new JwtTokenException("INVALID_TOKEN", "El token JWT no contiene un sujeto válido.");
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new JwtTokenException("INVALID_TOKEN", "El usuario asociado al token no existe."));

        String refreshedToken = jwtUtil.generateToken(user.username(), user.role());
        Instant expiresAt = jwtUtil.extractExpiration(refreshedToken);

        return new TokenRefreshResult(refreshedToken, user.role(), expiresAt, true);
    }

    @Override
    public UsernameUpdateResult updateUsername(String currentUsername, String newUsername) {
        if (currentUsername == null || currentUsername.isBlank()) {
            throw new BadCredentialsException("No autenticado");
        }

        String normalizedNewUsername = newUsername != null ? newUsername.trim() : "";
        if (normalizedNewUsername.length() < 3 || normalizedNewUsername.length() > 50) {
            throw new InvalidProfileUpdateException(
                    "INVALID_USERNAME",
                    "El nombre de usuario debe tener entre 3 y 50 caracteres."
            );
        }

        User user = userRepository.findByUsername(currentUsername).orElseThrow(UserNotFoundException::new);

        User savedUser = user;
        if (!normalizedNewUsername.equals(user.username())) {
            userRepository.findByUsername(normalizedNewUsername).ifPresent(existing -> {
                if (!existing.id().equals(user.id())) {
                    throw new ArticleBusinessConflictException(
                            "El nombre de usuario '" + normalizedNewUsername + "' ya está en uso."
                    );
                }
            });

            User updated = new User(
                    user.id(),
                    normalizedNewUsername,
                    user.email(),
                    user.password(),
                    user.phone(),
                    user.stripeCustomerId(),
                    user.role()
            );
            savedUser = userRepository.save(updated);
        }

        String newToken = jwtUtil.generateToken(savedUser.username(), savedUser.role());
        return new UsernameUpdateResult(savedUser, newToken);
    }

    @Override
    public void updatePassword(String currentUsername, String currentPassword, String newPassword) {
        if (currentUsername == null || currentUsername.isBlank()) {
            throw new BadCredentialsException("No autenticado");
        }
        if (currentPassword == null || currentPassword.isBlank()) {
            throw new InvalidProfileUpdateException(
                    "INVALID_PASSWORD",
                    "La contraseña actual es requerida."
            );
        }
        if (newPassword == null || newPassword.length() < 6 || newPassword.length() > 100) {
            throw new InvalidProfileUpdateException(
                    "INVALID_PASSWORD",
                    "La nueva contraseña debe tener entre 6 y 100 caracteres."
            );
        }

        User user = userRepository.findByUsername(currentUsername).orElseThrow(UserNotFoundException::new);

        if (!passwordEncoder.matches(currentPassword, user.password())) {
            throw new InvalidProfileUpdateException(
                    "INVALID_CURRENT_PASSWORD",
                    "La contraseña actual es incorrecta."
            );
        }

        if (passwordEncoder.matches(newPassword, user.password())) {
            throw new InvalidProfileUpdateException(
                    "SAME_PASSWORD",
                    "La nueva contraseña debe ser diferente a la actual."
            );
        }

        User updated = new User(
                user.id(),
                user.username(),
                user.email(),
                passwordEncoder.encode(newPassword),
                user.phone(),
                user.stripeCustomerId(),
                user.role()
        );
        userRepository.save(updated);
    }

    private Role resolveRole(String role) {
        if (role != null && role.trim().equalsIgnoreCase("ADMIN")) {
            return Role.ADMIN;
        }
        return Role.CLIENT;
    }
}
