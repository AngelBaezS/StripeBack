package org.angel.paymentclean.infrastructure.config;

import org.angel.paymentclean.domain.port.in.CreateOrderUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderExpirationScheduler.class);

    private final CreateOrderUseCase createOrderUseCase;

    public OrderExpirationScheduler(CreateOrderUseCase createOrderUseCase) {
        this.createOrderUseCase = createOrderUseCase;
    }

    @Scheduled(fixedDelayString = "${app.order.expiration-check-ms:300000}")
    public void cancelExpiredPendingOrders() {
        try {
            createOrderUseCase.cancelExpiredPendingOrders();
        } catch (Exception ex) {
            log.warn("Error al ejecutar la revisión automática de órdenes expiradas: {}", ex.getMessage());
        }
    }
}

