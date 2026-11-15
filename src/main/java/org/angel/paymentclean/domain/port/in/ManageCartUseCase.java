package org.angel.paymentclean.domain.port.in;

import org.angel.paymentclean.domain.model.CartDetail;

import java.util.List;

public interface ManageCartUseCase {
    CartDetail getCart(String username);
    List<CartDetail> getCartHistory(String username);
    CartDetail addItemToCart(String username, String productId, Integer quantity);
    CartDetail removeItemFromCart(String username, String itemOrProductId);
    void clearCart(String username);
}
