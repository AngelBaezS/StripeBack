package org.angel.paymentclean.infrastructure.adapters.out.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.angel.paymentclean.domain.enums.InvoiceStatus;
import org.angel.paymentclean.domain.util.IdGenerator;

import java.time.LocalDateTime;

@Entity
@Table(name = "order_invoices")
@Getter
@Setter
public class OrderInvoiceEntity {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "order_id", length = 36, nullable = false, unique = true)
    private String orderId;

    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    @Column(length = 13, nullable = false)
    private String rfc;

    @Column(name = "legal_name", length = 255, nullable = false)
    private String legalName;

    @Column(name = "zip_code", length = 10, nullable = false)
    private String zipCode;

    @Column(name = "tax_regime", length = 10, nullable = false)
    private String taxRegime;

    @Column(name = "cfdi_use", length = 10, nullable = false)
    private String cfdiUse;

    @Column(length = 255, nullable = false)
    private String email;

    @Column(name = "forma_pago", length = 10)
    private String formaPago;

    @Column(name = "cfdi_express_id", length = 100)
    private String cfdiExpressId;

    @Column(length = 100)
    private String uuid;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private InvoiceStatus status;

    @Column(name = "pdf_url", columnDefinition = "TEXT")
    private String pdfUrl;

    @Column(name = "xml_url", columnDefinition = "TEXT")
    private String xmlUrl;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "issued_at")
    private LocalDateTime issuedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public OrderInvoiceEntity() {}

    @PrePersist
    public void onPrePersist() {
        if (this.id == null || this.id.isBlank()) {
            this.id = IdGenerator.invoiceId();
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
