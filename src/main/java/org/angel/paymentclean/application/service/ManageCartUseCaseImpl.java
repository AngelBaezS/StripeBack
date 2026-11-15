package org.angel.paymentclean.application.service;

import org.angel.paymentclean.domain.enums.CartStatus;
import org.angel.paymentclean.domain.exception.ArticleBusinessConflictException;
import org.angel.paymentclean.domain.exception.ProductNotFoundException;
import org.angel.paymentclean.domain.exception.UserNotFoundException;
import org.angel.paymentclean.domain.model.Cart;
import org.angel.paymentclean.domain.model.CartDetail;
import org.angel.paymentclean.domain.model.CartItem;
import org.angel.paymentclean.domain.model.CartItemDetail;
import org.angel.paymentclean.domain.model.Product;
import org.angel.paymentclean.domain.model.User;
import org.angel.paymentclean.domain.port.in.ManageCartUseCase;
import org.angel.paymentclean.domain.port.out.CartRepositoryPort;
import org.angel.paymentclean.domain.port.out.ProductRepositoryPort;
import org.angel.paymentclean.domain.port.out.UserRepositoryPort;
import org.angel.paymentclean.domain.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class ManageCartUseCaseImpl implements ManageCartUseCase {

    private final CartRepositoryPort cartRepository;
    private final UserRepositoryPort userRepository;
    private final ProductRepositoryPort productRepository;

    public ManageCartUseCaseImpl(
            CartRepositoryPort cartRepository,
            UserRepositoryPort userRepository,
            ProductRepositoryPort productRepository) {
        this.cartRepository = cartRepository;
        this.userRepository = userRepository;
        this.productRepository = productRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public CartDetail getCart(String username) {
        User user = findUserByUsername(username);
        return cartRepository.findByUserIdAndStatus(user.id(), CartStatus.ACTIVE)
                .map(this::toCartDetail)
                .orElseGet(() -> new CartDetail(
                        null,
                        user.id(),
                        CartStatus.ACTIVE,
                        LocalDateTime.now(),
                        List.of(),
                        0L
                ));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CartDetail> getCartHistory(String username) {
        User user = findUserByUsername(username);
        return cartRepository.findByUserId(user.id()).stream()
                .map(this::toCartDetail)
                .toList();
    }

    @Override
    @Transactional
    public CartDetail addItemToCart(String username, String productId, Integer quantity) {
        User user = findUserByUsername(username);
        int qtyToAdd = (quantity == null || quantity <= 0) ? 1 : quantity;

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));

        if (product.active() != null && !product.active()) {
            throw new ArticleBusinessConflictException("El producto '" + product.name() + "' no se encuentra activo");
        }

        Cart currentCart = getOrCreateActiveCart(user.id());

        List<CartItem> updatedItems = new ArrayList<>(currentCart.items() != null ? currentCart.items() : List.of());
        int existingIndex = -1;
        int currentQuantity = 0;

        for (int i = 0; i < updatedItems.size(); i++) {
            CartItem item = updatedItems.get(i);
            if (item.productId().equals(productId)) {
                existingIndex = i;
                currentQuantity = item.quantity() != null ? item.quantity() : 0;
                break;
            }
        }

        int finalQuantity = currentQuantity + qtyToAdd;
        if (product.stock() != null && finalQuantity > product.stock()) {
            throw new ArticleBusinessConflictException(
                    "Stock insuficiente para el producto '" + product.name() +
                            "'. Solicitado en total: " + finalQuantity + ", Disponible: " + product.stock());
        }

        if (existingIndex >= 0) {
            CartItem existing = updatedItems.get(existingIndex);
            updatedItems.set(existingIndex, new CartItem(existing.id(), productId, finalQuantity));
        } else {
            updatedItems.add(new CartItem(IdGenerator.cartItemId(), productId, finalQuantity));
        }

        Cart cartToSave = new Cart(
                currentCart.id(),
                user.id(),
                CartStatus.ACTIVE,
                LocalDateTime.now(),
                updatedItems);

        Cart saved = cartRepository.save(cartToSave);
        return toCartDetail(saved);
    }

    @Override
    @Transactional
    public CartDetail removeItemFromCart(String username, String itemOrProductId) {
        User user = findUserByUsername(username);
        Cart currentCart = cartRepository.findByUserIdAndStatus(user.id(), CartStatus.ACTIVE)
                .orElseThrow(() -> new ArticleBusinessConflictException("El usuario no tiene un carrito activo"));

        List<CartItem> updatedItems = new ArrayList<>();
        if (currentCart.items() != null) {
            for (CartItem item : currentCart.items()) {
                boolean matchesItemId = item.id() != null && item.id().equals(itemOrProductId);
                boolean matchesProductId = item.productId() != null && item.productId().equals(itemOrProductId);
                if (!matchesItemId && !matchesProductId) {
                    updatedItems.add(item);
                }
            }
        }

        Cart cartToSave = new Cart(
                currentCart.id(),
                user.id(),
                CartStatus.ACTIVE,
                LocalDateTime.now(),
                updatedItems);

        Cart saved = cartRepository.save(cartToSave);
        return toCartDetail(saved);
    }

    @Override
    @Transactional
    public void clearCart(String username) {
        User user = findUserByUsername(username);
        cartRepository.findByUserIdAndStatus(user.id(), CartStatus.ACTIVE).ifPresent(cart -> {
            Cart clearedCart = new Cart(
                    cart.id(),
                    user.id(),
                    CartStatus.ACTIVE,
                    LocalDateTime.now(),
                    List.of());
            cartRepository.save(clearedCart);
        });
    }

    private Cart getOrCreateActiveCart(String userId) {
        return cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)
                .orElseGet(() -> {
                    Cart newCart = new Cart(
                            IdGenerator.cartId(),
                            userId,
                            CartStatus.ACTIVE,
                            LocalDateTime.now(),
                            List.of());
                    return cartRepository.save(newCart);
                });
    }

    private CartDetail toCartDetail(Cart cart) {
        long totalAmountCents = 0L;
        List<CartItemDetail> itemDetails = new ArrayList<>();

        if (cart.items() != null) {
            for (CartItem item : cart.items()) {
                Product product = productRepository.findById(item.productId()).orElse(null);
                if (product != null) {
                    long price = product.unitAmountCents() != null ? product.unitAmountCents() : 0L;
                    int qty = item.quantity() != null ? item.quantity() : 0;
                    long subtotal = price * qty;
                    totalAmountCents += subtotal;

                    itemDetails.add(new CartItemDetail(
                            item.id(),
                            product.id(),
                            product.name(),
                            product.description(),
                            product.imageUrl(),
                            price,
                            qty,
                            subtotal,
                            product.stock()));
                }
            }
        }

        return new CartDetail(
                cart.id(),
                cart.userId(),
                cart.status(),
                cart.updatedAt(),
                itemDetails,
                totalAmountCents);
    }

    private User findUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(UserNotFoundException::new);
    }
}
