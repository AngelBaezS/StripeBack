package org.angel.paymentclean.infrastructure.adapters.in.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.angel.paymentclean.domain.model.TokenRefreshResult;
import org.angel.paymentclean.domain.model.User;
import org.angel.paymentclean.domain.model.UsernameUpdateResult;
import org.angel.paymentclean.domain.port.in.AuthUserUseCase;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.CreateUserRequest;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.LoginUserRequest;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.MessageResponse;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.RefreshTokenRequest;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.RefreshTokenResponse;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.UpdatePasswordRequest;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.UpdateUsernameRequest;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.UpdateUsernameResponse;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.UserProfileResponse;
import org.angel.paymentclean.infrastructure.security.JwtCookieService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/v1/auth", "/auth"})
public class UserController {

    private final AuthUserUseCase authUserUseCase;
    private final JwtCookieService jwtCookieService;

    public UserController(AuthUserUseCase authUserUseCase) {
        this(authUserUseCase, new JwtCookieService());
    }

    @Autowired
    public UserController(AuthUserUseCase authUserUseCase, JwtCookieService jwtCookieService) {
        this.authUserUseCase = authUserUseCase;
        this.jwtCookieService = jwtCookieService;
    }

    @PostMapping("/register")
    public ResponseEntity<String> register(@RequestBody CreateUserRequest user) {
        return ResponseEntity.ok(
                authUserUseCase.register(user.username(), user.email(), user.password(), user.phone(), user.role())
        );
    }

    @PostMapping("/login")
    public ResponseEntity<String> login(@RequestBody LoginUserRequest user) {
        String token = authUserUseCase.login(user.username(), user.password());
        User authenticatedUser = authUserUseCase.getCurrentUser(user.username());
        ResponseCookie authCookie = jwtCookieService.createAuthCookie(token);
        long expiresAtMs = System.currentTimeMillis() + jwtCookieService.getExpirationMs();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, authCookie.toString())
                .header("X-User-Role", authenticatedUser.role().name())
                .header("X-Token-Expires-At", String.valueOf(expiresAtMs))
                .body("Autenticación exitosa.");
    }

    @PostMapping("/refresh")
    public ResponseEntity<RefreshTokenResponse> refresh(
            HttpServletRequest servletRequest,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @RequestBody(required = false) RefreshTokenRequest request
    ) {
        String token = jwtCookieService.extractTokenFromCookies(servletRequest);
        if (token == null || token.isBlank()) {
            token = extractBearerToken(authHeader);
        }
        if ((token == null || token.isBlank()) && request != null) {
            token = request.token();
        }
        TokenRefreshResult result = authUserUseCase.refreshToken(token);
        ResponseCookie refreshedCookie = jwtCookieService.createAuthCookie(result.token());
        long expiresAtMs = result.expiresAt() != null
                ? result.expiresAt().toEpochMilli()
                : System.currentTimeMillis() + jwtCookieService.getExpirationMs();

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshedCookie.toString())
                .header("X-User-Role", result.role().name())
                .header("X-Token-Expires-At", String.valueOf(expiresAtMs))
                .body(new RefreshTokenResponse(
                        null,
                        result.role(),
                        result.expiresAt(),
                        result.refreshed()
                ));
    }

    @PostMapping("/logout")
    public ResponseEntity<MessageResponse> logout() {
        ResponseCookie logoutCookie = jwtCookieService.createLogoutCookie();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, logoutCookie.toString())
                .body(new MessageResponse("Sesión cerrada correctamente."));
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> me(Authentication authentication) {
        String username = authentication != null ? authentication.getName() : null;
        User user = authUserUseCase.getCurrentUser(username);
        return ResponseEntity.ok(toProfileResponse(user));
    }

    @PutMapping("/me/username")
    public ResponseEntity<UpdateUsernameResponse> updateUsername(
            Authentication authentication,
            @Valid @RequestBody UpdateUsernameRequest request
    ) {
        String currentUsername = authentication != null ? authentication.getName() : null;
        UsernameUpdateResult result = authUserUseCase.updateUsername(currentUsername, request.username());
        ResponseCookie updatedCookie = jwtCookieService.createAuthCookie(result.token());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, updatedCookie.toString())
                .header("X-User-Role", result.user().role().name())
                .body(new UpdateUsernameResponse(toProfileResponse(result.user()), null));
    }

    @PutMapping("/me/password")
    public ResponseEntity<MessageResponse> updatePassword(
            Authentication authentication,
            @Valid @RequestBody UpdatePasswordRequest request
    ) {
        String currentUsername = authentication != null ? authentication.getName() : null;
        authUserUseCase.updatePassword(currentUsername, request.currentPassword(), request.newPassword());
        return ResponseEntity.ok(new MessageResponse("Contraseña actualizada correctamente."));
    }

    private UserProfileResponse toProfileResponse(User user) {
        return new UserProfileResponse(
                user.id(),
                user.username(),
                user.email(),
                user.phone(),
                user.role()
        );
    }

    private String extractBearerToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }
        return null;
    }
}
