package org.angel.paymentclean.domain.port.out;

import org.angel.paymentclean.domain.model.OrderInvoice;

import java.util.List;
import java.util.Optional;

public interface OrderInvoiceRepositoryPort {
    OrderInvoice save(OrderInvoice invoice);
    Optional<OrderInvoice> findByOrderId(String orderId);
    List<OrderInvoice> findByOrderIdIn(List<String> orderIds);
}
