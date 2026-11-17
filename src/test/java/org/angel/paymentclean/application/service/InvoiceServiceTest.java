package org.angel.paymentclean.application.service;

import org.angel.paymentclean.domain.enums.InvoiceStatus;
import org.angel.paymentclean.domain.enums.Status;
import org.angel.paymentclean.domain.exception.ArticleBusinessConflictException;
import org.angel.paymentclean.domain.model.ItemOrden;
import org.angel.paymentclean.domain.model.Order;
import org.angel.paymentclean.domain.model.OrderInvoice;
import org.angel.paymentclean.domain.model.Product;
import org.angel.paymentclean.domain.model.User;
import org.angel.paymentclean.domain.port.out.CfdiExpressPort;
import org.angel.paymentclean.domain.port.out.OrderInvoiceRepositoryPort;
import org.angel.paymentclean.domain.port.out.OrderRepositoryPort;
import org.angel.paymentclean.domain.port.out.ProductRepositoryPort;
import org.angel.paymentclean.domain.port.out.UserRepositoryPort;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.InvoiceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    @Mock
    private OrderInvoiceRepositoryPort invoiceRepository;
    @Mock
    private OrderRepositoryPort orderRepository;
    @Mock
    private UserRepositoryPort userRepository;
    @Mock
    private ProductRepositoryPort productRepository;
    @Mock
    private CfdiExpressPort cfdiExpressPort;
    @Mock
    private MailtrapEmailService mailtrapEmailService;

    private InvoiceServiceImpl service;

    private final User user = new User("usr_1", "angel", "angel@correo.mx", "pwd", "5512345678", "cus_1");

    @BeforeEach
    void setUp() {
        service = new InvoiceServiceImpl(
                invoiceRepository,
                orderRepository,
                userRepository,
                productRepository,
                cfdiExpressPort,
                mailtrapEmailService
        );
    }

    @Test
    void saveCheckoutInvoiceRequest_whenOrderIsPending_shouldStorePendingPaymentWithoutStampingYet() {
        Order pendingOrder = new Order(
                "ord_1", "usr_1", "crt_1", "cus_1", "pi_1", null, null, null,
                150000L, "mxn", Status.PENDING, List.of(), LocalDateTime.now(), null
        );
        InvoiceRequest request = new InvoiceRequest(
                "xia190128j61", "Xenon Industrial SA", "01160", "601", "G03", "facturas@cliente.mx"
        );

        when(userRepository.findByUsername("angel")).thenReturn(Optional.of(user));
        when(orderRepository.findById("ord_1")).thenReturn(Optional.of(pendingOrder));
        when(invoiceRepository.findByOrderId("ord_1")).thenReturn(Optional.empty());
        when(invoiceRepository.save(any(OrderInvoice.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.saveCheckoutInvoiceRequest("angel", "ord_1", request);

        assertEquals(InvoiceStatus.PENDING_PAYMENT, response.status());
        assertEquals("XIA190128J61", response.rfc());
        assertEquals("XENON INDUSTRIAL SA", response.legalName());
        assertEquals("04", response.formaPago());
        verifyNoInteractions(cfdiExpressPort);
        verifyNoInteractions(mailtrapEmailService);
    }

    @Test
    void issueInvoiceForOrder_whenOrderPaidAndNotInvoiced_shouldStampOnceAndSendMailtrapEmail() {
        Product product = new Product("prd_1", "Teclado Mecánico", "RGB", 10, true, "prod_1", "price_1", 174000L, "mxn");
        Order paidOrder = new Order(
                "ord_paid_1", "usr_1", "crt_1", "cus_1", "pi_paid_1", null, null, null,
                174000L, "mxn", Status.PAID, List.of(new ItemOrden("prd_1", "price_1", 1L)),
                LocalDateTime.now().minusHours(1), null
        );
        InvoiceRequest request = new InvoiceRequest(
                "XIA190128J61", "XENON INDUSTRIAL ARTICLES", "01160", "601", "G03", "facturas@empresa.mx"
        );
        byte[] pdfBytes = "%PDF-1.4\n".getBytes();
        byte[] xmlBytes = "<cfdi:Comprobante/>".getBytes();
        CfdiExpressPort.CfdiStampResult stampResult = new CfdiExpressPort.CfdiStampResult(
                "inv_cfdi_123",
                "A1B2C3D4-E5F6-7890-1234-567890ABCDEF",
                "stamped",
                "https://api.cfdi.express/v1/files/inv_cfdi_123/pdf",
                "https://api.cfdi.express/v1/files/inv_cfdi_123/xml",
                pdfBytes,
                xmlBytes
        );

        when(userRepository.findByUsername("angel")).thenReturn(Optional.of(user));
        when(orderRepository.findById("ord_paid_1")).thenReturn(Optional.of(paidOrder));
        when(invoiceRepository.findByOrderId("ord_paid_1")).thenReturn(Optional.empty());
        when(invoiceRepository.save(any(OrderInvoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productRepository.findById("prd_1")).thenReturn(Optional.of(product));
        when(cfdiExpressPort.stampInvoice(any())).thenReturn(stampResult);
        when(mailtrapEmailService.sendInvoiceEmail(any(), any(), any(), any())).thenReturn(true);

        var response = service.issueInvoiceForOrder("angel", "ord_paid_1", request);

        assertEquals(InvoiceStatus.STAMPED, response.status());
        assertEquals("A1B2C3D4-E5F6-7890-1234-567890ABCDEF", response.uuid());
        assertTrue(response.hasPdf());
        assertTrue(response.hasXml());
        assertEquals(Boolean.TRUE, response.emailSent());

        ArgumentCaptor<CfdiExpressPort.CfdiStampCommand> cmdCaptor =
                ArgumentCaptor.forClass(CfdiExpressPort.CfdiStampCommand.class);
        verify(cfdiExpressPort).stampInvoice(cmdCaptor.capture());
        assertEquals("04", cmdCaptor.getValue().formaPago());
        assertEquals("PUE", cmdCaptor.getValue().metodoPago());
        assertEquals(new BigDecimal("1740.00"), cmdCaptor.getValue().items().get(0).unitPricePesos());

        verify(mailtrapEmailService).sendInvoiceEmail(any(OrderInvoice.class), eq(paidOrder), eq(pdfBytes), eq(xmlBytes));
    }

    @Test
    void issueInvoiceForOrder_whenAlreadyStamped_shouldThrowConflictAndNeverStampTwice() {
        Order paidOrder = new Order(
                "ord_stamped", "usr_1", "crt_1", "cus_1", "pi_1", null, null, null,
                150000L, "mxn", Status.PAID, List.of(), LocalDateTime.now().minusMinutes(30), null
        );
        OrderInvoice stamped = new OrderInvoice(
                "invo_1", "ord_stamped", "usr_1", "XIA190128J61", "EMPRESA", "01160", "601", "G03",
                "a@b.mx", "04", "inv_999", "UUID-1234", InvoiceStatus.STAMPED,
                "https://pdf", "https://xml", null, LocalDateTime.now(), LocalDateTime.now()
        );
        InvoiceRequest request = new InvoiceRequest(
                "XIA190128J61", "EMPRESA", "01160", "601", "G03", "a@b.mx"
        );

        when(userRepository.findByUsername("angel")).thenReturn(Optional.of(user));
        when(orderRepository.findById("ord_stamped")).thenReturn(Optional.of(paidOrder));
        when(invoiceRepository.findByOrderId("ord_stamped")).thenReturn(Optional.of(stamped));

        assertThrows(ArticleBusinessConflictException.class,
                () -> service.issueInvoiceForOrder("angel", "ord_stamped", request));
        verifyNoInteractions(cfdiExpressPort);
        verifyNoInteractions(mailtrapEmailService);
    }

    @Test
    void processAutomaticInvoiceOnPaymentConfirmed_whenPendingPaymentExists_shouldStampSpeiOrderWithFormaPago03() {
        Order paidSpeiOrder = new Order(
                "ord_spei_1", "usr_1", "crt_1", "cus_1", "pi_spei", null, null,
                "https://payments.stripe.com/bank_transfer/instructions_test",
                200000L, "mxn", Status.PAID, List.of(), LocalDateTime.now(), null
        );
        OrderInvoice pendingInvoice = new OrderInvoice(
                "invo_spei_1", "ord_spei_1", "usr_1", "EKU9003173C9", "ESCUELA KEMPER", "76000", "601", "G03",
                "pagos@kemper.mx", "03", null, null, InvoiceStatus.PENDING_PAYMENT,
                null, null, null, null, LocalDateTime.now()
        );
        CfdiExpressPort.CfdiStampResult result = new CfdiExpressPort.CfdiStampResult(
                "inv_spei_cfdi", "UUID-SPEI-1", "stamped", "https://pdf", "https://xml",
                new byte[]{1, 2}, new byte[]{3, 4}
        );

        when(invoiceRepository.findByOrderId("ord_spei_1")).thenReturn(Optional.of(pendingInvoice));
        when(orderRepository.findById("ord_spei_1")).thenReturn(Optional.of(paidSpeiOrder));
        when(cfdiExpressPort.stampInvoice(any())).thenReturn(result);
        when(invoiceRepository.save(any(OrderInvoice.class))).thenAnswer(inv -> inv.getArgument(0));

        service.processAutomaticInvoiceOnPaymentConfirmed("ord_spei_1");

        ArgumentCaptor<OrderInvoice> savedCaptor = ArgumentCaptor.forClass(OrderInvoice.class);
        verify(invoiceRepository).save(savedCaptor.capture());
        assertEquals(InvoiceStatus.STAMPED, savedCaptor.getValue().status());
        assertEquals("UUID-SPEI-1", savedCaptor.getValue().uuid());
        verify(mailtrapEmailService).sendInvoiceEmail(any(), eq(paidSpeiOrder), any(), any());
    }

    @Test
    void resendInvoiceEmail_whenAlreadyStamped_shouldResendWithoutReStamping() {
        Order paidOrder = new Order(
                "ord_resend_1", "usr_1", "crt_1", "cus_1", "pi_1", null, null, null,
                150000L, "mxn", Status.PAID, List.of(), LocalDateTime.now(), null
        );
        OrderInvoice stamped = new OrderInvoice(
                "invo_1", "ord_resend_1", "usr_1", "XIA190128J61", "EMPRESA", "01160", "601", "G03",
                "original@b.mx", "04", "inv_999", "UUID-1234", InvoiceStatus.STAMPED,
                "https://pdf", "https://xml", null, LocalDateTime.now(), LocalDateTime.now()
        );
        byte[] pdfBytes = "%PDF".getBytes();
        byte[] xmlBytes = "<xml/>".getBytes();

        when(userRepository.findByUsername("angel")).thenReturn(Optional.of(user));
        when(orderRepository.findById("ord_resend_1")).thenReturn(Optional.of(paidOrder));
        when(invoiceRepository.findByOrderId("ord_resend_1")).thenReturn(Optional.of(stamped));
        when(invoiceRepository.save(any(OrderInvoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cfdiExpressPort.downloadFileBytes("https://pdf")).thenReturn(pdfBytes);
        when(cfdiExpressPort.downloadFileBytes("https://xml")).thenReturn(xmlBytes);
        when(mailtrapEmailService.sendInvoiceEmail(any(), eq(paidOrder), eq(pdfBytes), eq(xmlBytes)))
                .thenReturn(true);

        var response = service.resendInvoiceEmail("angel", "ord_resend_1", "angelbaezs1512@gmail.com");

        assertEquals(Boolean.TRUE, response.emailSent());
        assertEquals("angelbaezs1512@gmail.com", response.email());
        verify(cfdiExpressPort, never()).stampInvoice(any());
        verify(mailtrapEmailService).sendInvoiceEmail(any(), eq(paidOrder), eq(pdfBytes), eq(xmlBytes));
    }

    @Test
    void issueInvoiceForOrder_whenCfdiExpressThrows_shouldPersistFailedStatusInsideSeparateTx() {
        Order paidOrder = new Order(
                "ord_fail_inv", "usr_1", "crt_1", "cus_1", "pi_1", null, null, null,
                150000L, "mxn", Status.PAID, List.of(), LocalDateTime.now(), null
        );
        InvoiceRequest request = new InvoiceRequest(
                "XIA190128J61", "EMPRESA", "01160", "601", "G03", "a@b.mx"
        );

        when(userRepository.findByUsername("angel")).thenReturn(Optional.of(user));
        when(orderRepository.findById("ord_fail_inv")).thenReturn(Optional.of(paidOrder));
        when(invoiceRepository.findByOrderId("ord_fail_inv")).thenReturn(Optional.empty());
        when(invoiceRepository.save(any(OrderInvoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cfdiExpressPort.stampInvoice(any()))
                .thenThrow(new org.angel.paymentclean.domain.exception.CfdiIntegrationException("PAC rechazó RFC"));

        assertThrows(org.angel.paymentclean.domain.exception.CfdiIntegrationException.class,
                () -> service.issueInvoiceForOrder("angel", "ord_fail_inv", request));

        ArgumentCaptor<OrderInvoice> savedCaptor = ArgumentCaptor.forClass(OrderInvoice.class);
        verify(invoiceRepository, times(2)).save(savedCaptor.capture());
        List<OrderInvoice> saves = savedCaptor.getAllValues();
        assertEquals(InvoiceStatus.PENDING_PAYMENT, saves.get(0).status());
        assertEquals(InvoiceStatus.FAILED, saves.get(1).status());
        assertEquals("PAC rechazó RFC", saves.get(1).errorMessage());
    }

    @Test
    void downloadInvoicePdf_whenSignedUrlExpired_shouldRefreshFilesFromCfdiExpress() {
        Order paidOrder = new Order(
                "ord_exp_1", "usr_1", "crt_1", "cus_1", "pi_1", null, null, null,
                150000L, "mxn", Status.PAID, List.of(), LocalDateTime.now(), null
        );
        OrderInvoice stamped = new OrderInvoice(
                "invo_1", "ord_exp_1", "usr_1", "CACX7605101P8", "XOCHILT CASAS CHAVEZ", "36257", "612", "G03",
                "a@b.mx", "04", "cfdi_exp_1", "UUID-EXP-1", InvoiceStatus.STAMPED,
                "https://expired-pdf", "https://expired-xml", null, LocalDateTime.now(), LocalDateTime.now()
        );
        byte[] freshPdf = "%PDF-FRESH".getBytes();
        CfdiExpressPort.CfdiStampResult refreshed = new CfdiExpressPort.CfdiStampResult(
                "cfdi_exp_1", "UUID-EXP-1", "stamped",
                "https://fresh-pdf", "https://fresh-xml",
                freshPdf, "<xml/>".getBytes()
        );

        when(userRepository.findByUsername("angel")).thenReturn(Optional.of(user));
        when(orderRepository.findById("ord_exp_1")).thenReturn(Optional.of(paidOrder));
        when(invoiceRepository.findByOrderId("ord_exp_1")).thenReturn(Optional.of(stamped));
        when(cfdiExpressPort.downloadFileBytes("https://expired-pdf"))
                .thenThrow(new org.angel.paymentclean.domain.exception.CfdiIntegrationException("403 Expired"));
        when(cfdiExpressPort.downloadFileBytes("https://expired-xml"))
                .thenThrow(new org.angel.paymentclean.domain.exception.CfdiIntegrationException("403 Expired"));
        when(cfdiExpressPort.refreshInvoiceFiles("cfdi_exp_1")).thenReturn(refreshed);
        when(invoiceRepository.save(any(OrderInvoice.class))).thenAnswer(inv -> inv.getArgument(0));

        byte[] result = service.downloadInvoicePdf("angel", "ord_exp_1");

        assertArrayEquals(freshPdf, result);
        verify(cfdiExpressPort).refreshInvoiceFiles("cfdi_exp_1");
        verify(invoiceRepository).save(argThat(inv -> "https://fresh-pdf".equals(inv.pdfUrl())));
    }
}
