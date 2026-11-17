package org.angel.paymentclean.infrastructure.adapters.out.persistence.repository;

import org.angel.paymentclean.domain.model.OrderInvoice;
import org.angel.paymentclean.domain.port.out.OrderInvoiceRepositoryPort;
import org.angel.paymentclean.domain.util.IdGenerator;
import org.angel.paymentclean.infrastructure.adapters.out.persistence.entity.OrderInvoiceEntity;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class OrderInvoiceRepositoryAdapter implements OrderInvoiceRepositoryPort {

    private final DataOrderInvoiceRepository jpaRepository;

    public OrderInvoiceRepositoryAdapter(DataOrderInvoiceRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public OrderInvoice save(OrderInvoice invoice) {
        OrderInvoiceEntity entity = toEntity(invoice);
        OrderInvoiceEntity saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<OrderInvoice> findByOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return Optional.empty();
        }
        return jpaRepository.findByOrderId(orderId).map(this::toDomain);
    }

    @Override
    public List<OrderInvoice> findByOrderIdIn(List<String> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return List.of();
        }
        return jpaRepository.findByOrderIdIn(orderIds).stream()
                .map(this::toDomain)
                .toList();
    }

    private OrderInvoiceEntity toEntity(OrderInvoice invoice) {
        OrderInvoiceEntity entity = new OrderInvoiceEntity();
        entity.setId(invoice.id() != null && !invoice.id().isBlank() ? invoice.id() : IdGenerator.invoiceId());
        entity.setOrderId(invoice.orderId());
        entity.setUserId(invoice.userId());
        entity.setRfc(invoice.rfc());
        entity.setLegalName(invoice.legalName());
        entity.setZipCode(invoice.zipCode());
        entity.setTaxRegime(invoice.taxRegime());
        entity.setCfdiUse(invoice.cfdiUse());
        entity.setEmail(invoice.email());
        entity.setFormaPago(invoice.formaPago());
        entity.setCfdiExpressId(invoice.cfdiExpressId());
        entity.setUuid(invoice.uuid());
        entity.setStatus(invoice.status());
        entity.setPdfUrl(invoice.pdfUrl());
        entity.setXmlUrl(invoice.xmlUrl());
        entity.setErrorMessage(invoice.errorMessage());
        entity.setIssuedAt(invoice.issuedAt());
        entity.setCreatedAt(invoice.createdAt() != null ? invoice.createdAt() : LocalDateTime.now());
        return entity;
    }

    private OrderInvoice toDomain(OrderInvoiceEntity entity) {
        return new OrderInvoice(
                entity.getId(),
                entity.getOrderId(),
                entity.getUserId(),
                entity.getRfc(),
                entity.getLegalName(),
                entity.getZipCode(),
                entity.getTaxRegime(),
                entity.getCfdiUse(),
                entity.getEmail(),
                entity.getFormaPago(),
                entity.getCfdiExpressId(),
                entity.getUuid(),
                entity.getStatus(),
                entity.getPdfUrl(),
                entity.getXmlUrl(),
                entity.getErrorMessage(),
                entity.getIssuedAt(),
                entity.getCreatedAt()
        );
    }
}
