package org.angel.paymentclean.infrastructure.adapters.out.persistence.repository;

import org.angel.paymentclean.domain.enums.CartStatus;
import org.angel.paymentclean.infrastructure.adapters.out.persistence.entity.CartEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DataCartRepository extends JpaRepository<CartEntity, String> {
    Optional<CartEntity> findFirstByUserIdAndStatusOrderByUpdatedAtDesc(String userId, CartStatus status);
    List<CartEntity> findByUserIdOrderByUpdatedAtDesc(String userId);
}
