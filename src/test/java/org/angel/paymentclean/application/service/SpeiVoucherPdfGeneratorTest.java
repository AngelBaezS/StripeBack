package org.angel.paymentclean.application.service;

import org.angel.paymentclean.domain.enums.Status;
import org.angel.paymentclean.domain.model.ItemOrden;
import org.angel.paymentclean.domain.model.Order;
import org.angel.paymentclean.domain.model.SpeiPaymentDetails;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpeiVoucherPdfGeneratorTest {

    private final SpeiVoucherPdfGenerator generator = new SpeiVoucherPdfGenerator();

    @Test
    void generateVoucherPdf_shouldProduceValidPdfWithSpeiAndOrderData() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 11, 13, 14, 30);
        LocalDateTime expiresAt = createdAt.plusHours(48);
        Order order = new Order(
                "ord_spei_001",
                "usr_1",
                "crt_1",
                "cus_1",
                "pi_spei_001",
                null,
                null,
                "https://payments.stripe.com/instructions",
                259900L,
                "mxn",
                Status.PENDING,
                List.of(new ItemOrden("prd_1", "price_1", 2L)),
                createdAt,
                null
        );
        SpeiPaymentDetails spei = new SpeiPaymentDetails(
                "646180111812345678",
                "STP",
                "646",
                "REF987654",
                "https://payments.stripe.com/instructions",
                259900L,
                "mxn",
                "ord_spei_001",
                "pagos@cliente.mx",
                expiresAt
        );
        List<SpeiVoucherPdfGenerator.VoucherItem> items = List.of(
                new SpeiVoucherPdfGenerator.VoucherItem("Laptop Pro 16", 2L, 129950L, 259900L)
        );

        byte[] pdfBytes = generator.generateVoucherPdf(order, spei, "angel", "pagos@cliente.mx", expiresAt, items);

        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 500);

        String pdfText = new String(pdfBytes, StandardCharsets.ISO_8859_1);
        assertTrue(pdfText.startsWith("%PDF-1.4"));
        assertTrue(pdfText.trim().endsWith("%%EOF"));
        assertTrue(pdfText.contains("ord_spei_001"));
        assertTrue(pdfText.contains("646180111812345678"));
        assertTrue(pdfText.contains("REF987654"));
        assertTrue(pdfText.contains("$2,599.00 MXN"));
        assertTrue(pdfText.contains("pagos@cliente.mx"));
        assertTrue(pdfText.contains("Laptop Pro 16"));
        assertTrue(pdfText.contains("(Total) Tj"));
    }

    @Test
    void generateVoucherPdf_whenManyItems_shouldPaginateCleanly() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 11, 13, 14, 30);
        LocalDateTime expiresAt = createdAt.plusHours(48);
        Order order = new Order(
                "ord_spei_many",
                "usr_1",
                "crt_1",
                "cus_1",
                "pi_spei_many",
                null,
                null,
                null,
                100000L,
                "mxn",
                Status.PENDING,
                List.of(),
                createdAt,
                null
        );
        SpeiPaymentDetails spei = new SpeiPaymentDetails(
                "646180111800000001",
                "STP",
                "646",
                "REFMANY",
                null,
                100000L,
                "mxn"
        );
        List<SpeiVoucherPdfGenerator.VoucherItem> items = java.util.stream.IntStream.rangeClosed(1, 25)
                .mapToObj(i -> new SpeiVoucherPdfGenerator.VoucherItem("Producto #" + i, 1L, 4000L, 4000L))
                .toList();

        byte[] pdfBytes = generator.generateVoucherPdf(order, spei, "angel", "pagos@cliente.mx", expiresAt, items);
        String pdfText = new String(pdfBytes, StandardCharsets.ISO_8859_1);

        assertTrue(pdfText.startsWith("%PDF-1.4"));
        assertTrue(pdfText.contains("/Count 2"));
        assertTrue(pdfText.contains("CONTINUACION"));
        assertTrue(pdfText.contains("Producto #25"));
    }
}

