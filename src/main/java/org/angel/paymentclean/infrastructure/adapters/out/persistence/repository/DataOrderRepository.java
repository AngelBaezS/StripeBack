package org.angel.paymentclean.infrastructure.adapters.out.persistence.repository;

import org.angel.paymentclean.domain.enums.Status;
import org.angel.paymentclean.infrastructure.adapters.out.persistence.entity.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DataOrderRepository extends JpaRepository<OrderEntity, String> {
    List<OrderEntity> findByUserIdOrderByCreatedAtDescIdDesc(String userId);
    Optional<OrderEntity> findByStripePaymentIntentId(String stripePaymentIntentId);
    List<OrderEntity> findByStatusAndCreatedAtBefore(Status status, LocalDateTime cutoff);
}
