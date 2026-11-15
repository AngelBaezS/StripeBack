package org.angel.paymentclean.domain.port.in;

import org.angel.paymentclean.domain.model.TokenRefreshResult;
import org.angel.paymentclean.domain.model.User;
import org.angel.paymentclean.domain.model.UsernameUpdateResult;

public interface AuthUserUseCase {
    String register(String username, String email, String password, String phone);
    String register(String username, String email, String password, String phone, String role);
    String login(String username, String password);
    User getCurrentUser(String username);
    TokenRefreshResult refreshToken(String token);
    UsernameUpdateResult updateUsername(String currentUsername, String newUsername);
    void updatePassword(String currentUsername, String currentPassword, String newPassword);
}
