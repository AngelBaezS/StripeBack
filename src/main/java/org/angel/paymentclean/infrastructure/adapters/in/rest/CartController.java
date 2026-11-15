package org.angel.paymentclean.infrastructure.adapters.in.rest;

import org.angel.paymentclean.domain.model.CartDetail;
import org.angel.paymentclean.domain.port.in.ManageCartUseCase;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.AddCartItemRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cart")
public class CartController {

    private final ManageCartUseCase manageCartUseCase;

    public CartController(ManageCartUseCase manageCartUseCase) {
        this.manageCartUseCase = manageCartUseCase;
    }

    @GetMapping
    public ResponseEntity<CartDetail> getCart(Authentication authentication) {
        String username = authentication.getName();
        return ResponseEntity.ok(manageCartUseCase.getCart(username));
    }

    @GetMapping("/history")
    public ResponseEntity<java.util.List<CartDetail>> getCartHistory(Authentication authentication) {
        String username = authentication.getName();
        return ResponseEntity.ok(manageCartUseCase.getCartHistory(username));
    }

    @PostMapping("/items")
    public ResponseEntity<CartDetail> addItem(
            @RequestBody AddCartItemRequest request,
            Authentication authentication) {
        String username = authentication.getName();
        CartDetail updatedCart = manageCartUseCase.addItemToCart(username, request.productId(), request.quantity());
        return ResponseEntity.ok(updatedCart);
    }

    @DeleteMapping("/items/{id}")
    public ResponseEntity<CartDetail> removeItem(
            @PathVariable String id,
            Authentication authentication) {
        String username = authentication.getName();
        CartDetail updatedCart = manageCartUseCase.removeItemFromCart(username, id);
        return ResponseEntity.ok(updatedCart);
    }

    @DeleteMapping
    public ResponseEntity<Void> clearCart(Authentication authentication) {
        String username = authentication.getName();
        manageCartUseCase.clearCart(username);
        return ResponseEntity.noContent().build();
    }
}


