package org.angel.paymentclean.infrastructure.adapters.out.persistence.repository;

import org.angel.paymentclean.infrastructure.adapters.out.persistence.entity.OrderInvoiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DataOrderInvoiceRepository extends JpaRepository<OrderInvoiceEntity, String> {
    Optional<OrderInvoiceEntity> findByOrderId(String orderId);
    List<OrderInvoiceEntity> findByOrderIdIn(List<String> orderIds);
}
