package org.angel.paymentclean.domain.port.out;

import org.angel.paymentclean.domain.enums.CartStatus;
import org.angel.paymentclean.domain.model.Cart;

import java.util.List;
import java.util.Optional;

public interface CartRepositoryPort {
    Optional<Cart> findById(String id);
    Optional<Cart> findByUserIdAndStatus(String userId, CartStatus status);
    List<Cart> findByUserId(String userId);
    Cart save(Cart cart);
}
