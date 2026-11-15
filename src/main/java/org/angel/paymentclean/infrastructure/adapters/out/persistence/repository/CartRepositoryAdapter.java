package org.angel.paymentclean.infrastructure.adapters.out.persistence.repository;

import org.angel.paymentclean.domain.enums.CartStatus;
import org.angel.paymentclean.domain.model.Cart;
import org.angel.paymentclean.domain.model.CartItem;
import org.angel.paymentclean.domain.port.out.CartRepositoryPort;
import org.angel.paymentclean.infrastructure.adapters.out.persistence.entity.CartEntity;
import org.angel.paymentclean.infrastructure.adapters.out.persistence.entity.CartItemEntity;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class CartRepositoryAdapter implements CartRepositoryPort {

    private final DataCartRepository dataCartRepository;

    public CartRepositoryAdapter(DataCartRepository dataCartRepository) {
        this.dataCartRepository = dataCartRepository;
    }

    @Override
    public Optional<Cart> findById(String id) {
        return dataCartRepository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<Cart> findByUserIdAndStatus(String userId, CartStatus status) {
        return dataCartRepository.findFirstByUserIdAndStatusOrderByUpdatedAtDesc(userId, status)
                .map(this::toDomain);
    }

    @Override
    public List<Cart> findByUserId(String userId) {
        return dataCartRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Cart save(Cart cart) {
        CartEntity entity;
        if (cart.id() != null) {
            entity = dataCartRepository.findById(cart.id())
                    .orElseGet(() -> new CartEntity(cart.id(), cart.userId(), cart.status(), cart.updatedAt()));
        } else {
            entity = new CartEntity(null, cart.userId(), cart.status(), cart.updatedAt());
        }

        entity.setStatus(cart.status() != null ? cart.status() : CartStatus.ACTIVE);
        entity.setUpdatedAt(cart.updatedAt() != null ? cart.updatedAt() : LocalDateTime.now());

        List<CartItem> domainItems = cart.items() != null ? cart.items() : Collections.emptyList();
        Set<String> domainProductIds = domainItems.stream()
                .map(CartItem::productId)
                .collect(Collectors.toSet());

        // 1. Quitar items que ya no están en el carrito
        entity.getItems().removeIf(itemEntity -> !domainProductIds.contains(itemEntity.getProductId()));

        // 2. Actualizar existentes o agregar nuevos
        for (CartItem domainItem : domainItems) {
            Optional<CartItemEntity> existing = entity.getItems().stream()
                    .filter(e -> e.getProductId().equals(domainItem.productId()))
                    .findFirst();

            if (existing.isPresent()) {
                existing.get().setQuantity(domainItem.quantity());
            } else {
                entity.addItem(domainItem.id(), domainItem.productId(), domainItem.quantity());
            }
        }

        CartEntity saved = dataCartRepository.save(entity);
        return toDomain(saved);
    }

    private Cart toDomain(CartEntity entity) {
        List<CartItem> items = entity.getItems() != null
                ? entity.getItems().stream()
                    .map(item -> new CartItem(item.getId(), item.getProductId(), item.getQuantity()))
                    .toList()
                : Collections.emptyList();

        return new Cart(
                entity.getId(),
                entity.getUserId(),
                entity.getStatus(),
                entity.getUpdatedAt(),
                items
        );
    }
}
