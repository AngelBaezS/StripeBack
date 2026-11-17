package org.angel.paymentclean.application.service;

import org.angel.paymentclean.domain.enums.InvoiceStatus;
import org.angel.paymentclean.domain.enums.Status;
import org.angel.paymentclean.domain.exception.ArticleBusinessConflictException;
import org.angel.paymentclean.domain.exception.CfdiIntegrationException;
import org.angel.paymentclean.domain.exception.UserNotFoundException;
import org.angel.paymentclean.domain.model.ItemOrden;
import org.angel.paymentclean.domain.model.Order;
import org.angel.paymentclean.domain.model.OrderInvoice;
import org.angel.paymentclean.domain.model.Product;
import org.angel.paymentclean.domain.model.User;
import org.angel.paymentclean.domain.port.in.InvoiceUseCase;
import org.angel.paymentclean.domain.port.out.CfdiExpressPort;
import org.angel.paymentclean.domain.port.out.OrderInvoiceRepositoryPort;
import org.angel.paymentclean.domain.port.out.OrderRepositoryPort;
import org.angel.paymentclean.domain.port.out.ProductRepositoryPort;
import org.angel.paymentclean.domain.port.out.UserRepositoryPort;
import org.angel.paymentclean.domain.util.IdGenerator;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.InvoiceRequest;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.InvoiceSummaryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@Service
public class InvoiceServiceImpl implements InvoiceUseCase {

    private static final Logger log = LoggerFactory.getLogger(InvoiceServiceImpl.class);

    private final OrderInvoiceRepositoryPort invoiceRepository;
    private final OrderRepositoryPort orderRepository;
    private final UserRepositoryPort userRepository;
    private final ProductRepositoryPort productRepository;
    private final CfdiExpressPort cfdiExpressPort;
    private final MailtrapEmailService mailtrapEmailService;
    private final String defaultProductCode;
    private final String defaultUnitCode;
    private final String storageDir;
    private final TransactionTemplate requiresNewTxTemplate;

    @Autowired
    public InvoiceServiceImpl(
            OrderInvoiceRepositoryPort invoiceRepository,
            OrderRepositoryPort orderRepository,
            UserRepositoryPort userRepository,
            ProductRepositoryPort productRepository,
            CfdiExpressPort cfdiExpressPort,
            MailtrapEmailService mailtrapEmailService,
            @Value("${cfdi.express.default-product-code:01010101}") String defaultProductCode,
            @Value("${cfdi.express.default-unit-code:H87}") String defaultUnitCode,
            @Value("${app.invoices.storage-dir:./data/invoices}") String storageDir,
            ObjectProvider<PlatformTransactionManager> transactionManagerProvider
    ) {
        this.invoiceRepository = invoiceRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.productRepository = productRepository;
        this.cfdiExpressPort = cfdiExpressPort;
        this.mailtrapEmailService = mailtrapEmailService;
        this.defaultProductCode = (defaultProductCode != null && !defaultProductCode.isBlank())
                ? defaultProductCode.trim() : "01010101";
        this.defaultUnitCode = (defaultUnitCode != null && !defaultUnitCode.isBlank())
                ? defaultUnitCode.trim() : "H87";
        this.storageDir = storageDir != null ? storageDir.trim() : "";

        PlatformTransactionManager txManager = transactionManagerProvider != null
                ? transactionManagerProvider.getIfAvailable()
                : null;
        if (txManager != null) {
            TransactionTemplate template = new TransactionTemplate(txManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.requiresNewTxTemplate = template;
        } else {
            this.requiresNewTxTemplate = null;
        }
    }

    public InvoiceServiceImpl(
            OrderInvoiceRepositoryPort invoiceRepository,
            OrderRepositoryPort orderRepository,
            UserRepositoryPort userRepository,
            ProductRepositoryPort productRepository,
            CfdiExpressPort cfdiExpressPort,
            MailtrapEmailService mailtrapEmailService,
            String defaultProductCode,
            String defaultUnitCode
    ) {
        this(
                invoiceRepository,
                orderRepository,
                userRepository,
                productRepository,
                cfdiExpressPort,
                mailtrapEmailService,
                defaultProductCode,
                defaultUnitCode,
                "",
                null
        );
    }

    public InvoiceServiceImpl(
            OrderInvoiceRepositoryPort invoiceRepository,
            OrderRepositoryPort orderRepository,
            UserRepositoryPort userRepository,
            ProductRepositoryPort productRepository,
            CfdiExpressPort cfdiExpressPort,
            MailtrapEmailService mailtrapEmailService
    ) {
        this(
                invoiceRepository,
                orderRepository,
                userRepository,
                productRepository,
                cfdiExpressPort,
                mailtrapEmailService,
                "01010101",
                "H87",
                "",
                null
        );
    }

    private <T> T runInTransaction(Supplier<T> action) {
        if (requiresNewTxTemplate != null) {
            return requiresNewTxTemplate.execute(status -> action.get());
        }
        return action.get();
    }

    @Override
    public InvoiceSummaryResponse saveCheckoutInvoiceRequest(String username, String orderId, InvoiceRequest request) {
        User user = requireUser(username);
        Order order = requireOwnedOrder(user, orderId);

        if (order.status() == Status.CANCELED || order.status() == Status.FAILED) {
            throw new ArticleBusinessConflictException("No es posible registrar facturación para una orden cancelada o fallida.");
        }

        OrderInvoice existing = invoiceRepository.findByOrderId(order.id()).orElse(null);
        ensureNotAlreadyStamped(existing);

        OrderInvoice toSave = buildDraftInvoice(existing, order, user.id(), request, InvoiceStatus.PENDING_PAYMENT);
        OrderInvoice saved = runInTransaction(() -> invoiceRepository.save(toSave));

        if (order.status() == Status.PAID) {
            StampOutcome outcome = stampPersistAndNotify(order, saved);
            return toSummaryResponse(outcome.invoice(), outcome.emailSent());
        }

        log.info("📝 [Facturación Checkout] Datos fiscales registrados para orden {} (RFC: {}, estado: PENDING_PAYMENT).",
                order.id(), saved.rfc());
        return toSummaryResponse(saved);
    }

    @Override
    public InvoiceSummaryResponse issueInvoiceForOrder(String username, String orderId, InvoiceRequest request) {
        User user = requireUser(username);
        Order order = requireOwnedOrder(user, orderId);

        if (order.status() != Status.PAID) {
            throw new ArticleBusinessConflictException(
                    "Solo se puede emitir la factura cuando el pago de la orden ha sido confirmado (estado PAID)."
            );
        }

        OrderInvoice existing = invoiceRepository.findByOrderId(order.id()).orElse(null);
        ensureNotAlreadyStamped(existing);

        OrderInvoice draft = buildDraftInvoice(existing, order, user.id(), request, InvoiceStatus.PENDING_PAYMENT);
        OrderInvoice savedDraft = runInTransaction(() -> invoiceRepository.save(draft));

        StampOutcome outcome = stampPersistAndNotify(order, savedDraft);
        return toSummaryResponse(outcome.invoice(), outcome.emailSent());
    }

    @Override
    public InvoiceSummaryResponse resendInvoiceEmail(String username, String orderId, String overrideEmail) {
        User user = requireUser(username);
        Order order = requireOwnedOrder(user, orderId);

        OrderInvoice invoice = invoiceRepository.findByOrderId(order.id())
                .orElseThrow(() -> new ArticleBusinessConflictException("La orden " + orderId + " aún no tiene factura emitida."));
        if (invoice.status() != InvoiceStatus.STAMPED) {
            throw new ArticleBusinessConflictException("Solo se puede reenviar una factura que ya fue timbrada (STAMPED).");
        }

        String effectiveEmail = (overrideEmail != null && !overrideEmail.isBlank())
                ? overrideEmail.trim().toLowerCase()
                : invoice.email();

        ArtifactBundle bundle = resolveArtifactsAndSyncInvoice(invoice, effectiveEmail, false);
        invoice = bundle.invoice();

        boolean sent = mailtrapEmailService != null
                && mailtrapEmailService.sendInvoiceEmail(invoice, order, bundle.pdfBytes(), bundle.xmlBytes());
        if (!sent) {
            throw new CfdiIntegrationException(
                    "No se pudo enviar la factura por correo mediante Mailtrap. "
                            + "Verifica MAILTRAP_TOKEN / MAILTRAP_OVERRIDE_TO en .env y los logs en https://mailtrap.io/sending/email_logs."
            );
        }

        return toSummaryResponse(invoice, true);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasPendingInvoiceRequest(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return false;
        }
        return invoiceRepository.findByOrderId(orderId)
                .map(inv -> inv.status() == InvoiceStatus.PENDING_PAYMENT)
                .orElse(false);
    }

    @Override
    public void processAutomaticInvoiceOnPaymentConfirmed(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return;
        }
        OrderInvoice pendingInvoice = invoiceRepository.findByOrderId(orderId).orElse(null);
        if (pendingInvoice == null || pendingInvoice.status() != InvoiceStatus.PENDING_PAYMENT) {
            return;
        }

        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.status() != Status.PAID) {
            return;
        }

        try {
            stampPersistAndNotify(order, pendingInvoice);
        } catch (Exception ex) {
            log.warn("⚠️ [Facturación Automática] No se pudo timbrar automáticamente la orden {}: {}",
                    orderId, ex.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InvoiceSummaryResponse> getInvoiceForOrder(String username, String orderId) {
        User user = requireUser(username);
        Order order = requireOwnedOrder(user, orderId);
        return invoiceRepository.findByOrderId(order.id()).map(InvoiceServiceImpl::toSummaryResponse);
    }

    @Override
    public byte[] downloadInvoicePdf(String username, String orderId) {
        return downloadStampedInvoiceArtifact(username, orderId, true);
    }

    @Override
    public byte[] downloadInvoiceXml(String username, String orderId) {
        return downloadStampedInvoiceArtifact(username, orderId, false);
    }

    private byte[] downloadStampedInvoiceArtifact(String username, String orderId, boolean isPdf) {
        User user = requireUser(username);
        Order order = requireOwnedOrder(user, orderId);

        OrderInvoice invoice = invoiceRepository.findByOrderId(order.id())
                .orElseThrow(() -> new ArticleBusinessConflictException("La orden " + orderId + " aún no tiene factura emitida."));
        if (invoice.status() != InvoiceStatus.STAMPED) {
            throw new ArticleBusinessConflictException("La factura de la orden " + orderId + " aún no ha sido timbrada.");
        }

        ArtifactBundle bundle = resolveArtifactsAndSyncInvoice(invoice, invoice.email(), true);
        byte[] requested = isPdf ? bundle.pdfBytes() : bundle.xmlBytes();
        if (requested != null && requested.length > 0) {
            return requested;
        }
        String fileUrl = isPdf ? bundle.invoice().pdfUrl() : bundle.invoice().xmlUrl();
        return cfdiExpressPort.downloadFileBytes(fileUrl);
    }

    private record ArtifactBundle(OrderInvoice invoice, byte[] pdfBytes, byte[] xmlBytes) {}

    private ArtifactBundle resolveArtifactsAndSyncInvoice(
            OrderInvoice invoice,
            String effectiveEmail,
            boolean throwOnDownloadFailure
    ) {
        byte[] pdfBytes = readCachedArtifact(invoice.orderId(), true);
        byte[] xmlBytes = readCachedArtifact(invoice.orderId(), false);

        String pdfUrl = invoice.pdfUrl();
        String xmlUrl = invoice.xmlUrl();
        String uuid = invoice.uuid();
        boolean needsUrlRefresh = (pdfBytes == null && (pdfUrl == null || pdfUrl.isBlank()))
                || (xmlBytes == null && (xmlUrl == null || xmlUrl.isBlank()));

        if (!needsUrlRefresh) {
            if (pdfBytes == null && pdfUrl != null && !pdfUrl.isBlank()) {
                pdfBytes = throwOnDownloadFailure
                        ? tryDownloadOrMarkExpired(pdfUrl)
                        : tryDownloadFileBytes(pdfUrl);
                if (pdfBytes == null) {
                    needsUrlRefresh = true;
                }
            }
            if (xmlBytes == null && xmlUrl != null && !xmlUrl.isBlank()) {
                xmlBytes = throwOnDownloadFailure
                        ? tryDownloadOrMarkExpired(xmlUrl)
                        : tryDownloadFileBytes(xmlUrl);
                if (xmlBytes == null) {
                    needsUrlRefresh = true;
                }
            }
        }

        boolean emailChanged = effectiveEmail != null && !effectiveEmail.equals(invoice.email());
        if (needsUrlRefresh && invoice.cfdiExpressId() != null && !invoice.cfdiExpressId().isBlank()) {
            CfdiExpressPort.CfdiStampResult refreshed = cfdiExpressPort.refreshInvoiceFiles(invoice.cfdiExpressId());
            if (refreshed != null) {
                pdfUrl = nonBlank(refreshed.pdfUrl(), pdfUrl);
                xmlUrl = nonBlank(refreshed.xmlUrl(), xmlUrl);
                uuid = nonBlank(refreshed.uuid(), uuid);
                if (refreshed.pdfBytes() != null) {
                    pdfBytes = refreshed.pdfBytes();
                }
                if (refreshed.xmlBytes() != null) {
                    xmlBytes = refreshed.xmlBytes();
                }
                final String finalPdfUrl = pdfUrl;
                final String finalXmlUrl = xmlUrl;
                final String finalUuid = uuid;
                final OrderInvoice currentInv = invoice;
                invoice = runInTransaction(() -> invoiceRepository.save(copyStampedWithUpdates(
                        currentInv, effectiveEmail, finalUuid, finalPdfUrl, finalXmlUrl
                )));
            }
        } else if (emailChanged) {
            final OrderInvoice currentInv = invoice;
            invoice = runInTransaction(() -> invoiceRepository.save(copyStampedWithUpdates(
                    currentInv, effectiveEmail, currentInv.uuid(), currentInv.pdfUrl(), currentInv.xmlUrl()
            )));
        }

        cacheArtifactsLocally(invoice.orderId(), pdfBytes, xmlBytes);
        return new ArtifactBundle(invoice, pdfBytes, xmlBytes);
    }

    private byte[] tryDownloadOrMarkExpired(String url) {
        try {
            return cfdiExpressPort.downloadFileBytes(url);
        } catch (Exception ex) {
            log.debug("⚠️ [Facturación] Descarga desde URL falló ({}), intentando refrescar desde CFDI Express.", ex.getMessage());
            return null;
        }
    }

    private OrderInvoice copyStampedWithUpdates(
            OrderInvoice source,
            String email,
            String uuid,
            String pdfUrl,
            String xmlUrl
    ) {
        return new OrderInvoice(
                source.id(), source.orderId(), source.userId(),
                source.rfc(), source.legalName(), source.zipCode(),
                source.taxRegime(), source.cfdiUse(), email,
                source.formaPago(), source.cfdiExpressId(),
                uuid,
                InvoiceStatus.STAMPED,
                pdfUrl,
                xmlUrl,
                null,
                source.issuedAt(),
                source.createdAt()
        );
    }

    private record StampOutcome(OrderInvoice invoice, boolean emailSent) {}

    private StampOutcome stampPersistAndNotify(Order order, OrderInvoice draft) {
        try {
            List<CfdiExpressPort.CfdiInvoiceLineItem> lineItems = buildCfdiLineItems(order);
            CfdiExpressPort.CfdiStampCommand command = new CfdiExpressPort.CfdiStampCommand(
                    order.id(),
                    draft.rfc(),
                    draft.legalName(),
                    draft.zipCode(),
                    draft.taxRegime(),
                    draft.cfdiUse(),
                    nonBlank(draft.formaPago(), resolveFormaPago(order)),
                    "PUE",
                    lineItems
            );

            CfdiExpressPort.CfdiStampResult result = cfdiExpressPort.stampInvoice(command);
            cacheArtifactsLocally(order.id(), result.pdfBytes(), result.xmlBytes());

            OrderInvoice stamped = runInTransaction(() -> invoiceRepository.save(new OrderInvoice(
                    draft.id(),
                    draft.orderId(),
                    draft.userId(),
                    draft.rfc(),
                    draft.legalName(),
                    draft.zipCode(),
                    draft.taxRegime(),
                    draft.cfdiUse(),
                    draft.email(),
                    nonBlank(draft.formaPago(), resolveFormaPago(order)),
                    result.cfdiExpressId(),
                    nonBlank(result.uuid(), result.cfdiExpressId()),
                    InvoiceStatus.STAMPED,
                    result.pdfUrl(),
                    result.xmlUrl(),
                    null,
                    LocalDateTime.now(),
                    draft.createdAt()
            )));

            boolean emailSent = false;
            if (mailtrapEmailService != null) {
                try {
                    emailSent = mailtrapEmailService.sendInvoiceEmail(stamped, order, result.pdfBytes(), result.xmlBytes());
                } catch (Exception emailEx) {
                    log.warn("⚠️ [Mailtrap] Factura {} timbrada correctamente pero falló el envío por correo: {}",
                            stamped.orderId(), emailEx.getMessage());
                }
            }

            return new StampOutcome(stamped, emailSent);
        } catch (RuntimeException ex) {
            runInTransaction(() -> invoiceRepository.save(new OrderInvoice(
                    draft.id(),
                    draft.orderId(),
                    draft.userId(),
                    draft.rfc(),
                    draft.legalName(),
                    draft.zipCode(),
                    draft.taxRegime(),
                    draft.cfdiUse(),
                    draft.email(),
                    draft.formaPago(),
                    draft.cfdiExpressId(),
                    draft.uuid(),
                    InvoiceStatus.FAILED,
                    draft.pdfUrl(),
                    draft.xmlUrl(),
                    ex.getMessage(),
                    draft.issuedAt(),
                    draft.createdAt()
            )));
            throw ex;
        }
    }

    private void cacheArtifactsLocally(String orderId, byte[] pdfBytes, byte[] xmlBytes) {
        if (storageDir == null || storageDir.isBlank() || orderId == null || orderId.isBlank()) {
            return;
        }
        writeArtifactFile(orderId, true, pdfBytes);
        writeArtifactFile(orderId, false, xmlBytes);
    }

    private void writeArtifactFile(String orderId, boolean isPdf, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        try {
            Path target = resolveArtifactPath(orderId, isPdf);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException ex) {
            log.warn("⚠️ [Facturación] No se pudo guardar archivo {} localmente para orden {}: {}",
                    isPdf ? "PDF" : "XML", orderId, ex.getMessage());
        }
    }

    private byte[] readCachedArtifact(String orderId, boolean isPdf) {
        if (storageDir == null || storageDir.isBlank() || orderId == null || orderId.isBlank()) {
            return null;
        }
        try {
            Path target = resolveArtifactPath(orderId, isPdf);
            if (Files.isRegularFile(target) && Files.size(target) > 0) {
                return Files.readAllBytes(target);
            }
        } catch (IOException ex) {
            log.debug("⚠️ [Facturación] No se pudo leer caché local {} para orden {}: {}",
                    isPdf ? "PDF" : "XML", orderId, ex.getMessage());
        }
        return null;
    }

    private Path resolveArtifactPath(String orderId, boolean isPdf) {
        String safeOrderId = orderId.replaceAll("[^A-Za-z0-9_-]", "_");
        String ext = isPdf ? ".pdf" : ".xml";
        return Path.of(storageDir, safeOrderId, "factura-" + safeOrderId + ext);
    }

    private List<CfdiExpressPort.CfdiInvoiceLineItem> buildCfdiLineItems(Order order) {
        long orderTotalCents = order.totalAmountCents() != null ? order.totalAmountCents() : 0L;
        if (order.items() == null || order.items().isEmpty()) {
            return List.of(singleSummaryItem(order.id(), orderTotalCents));
        }

        List<CfdiExpressPort.CfdiInvoiceLineItem> raw = new ArrayList<>();
        Map<String, CfdiExpressPort.CfdiInvoiceLineItem> dedup = new LinkedHashMap<>();
        long rawSumCents = 0L;
        long dedupSumCents = 0L;

        for (ItemOrden item : order.items()) {
            Product product = productRepository.findById(item.productId()).orElse(null);
            if (product == null || product.unitAmountCents() == null || product.unitAmountCents() <= 0) {
                return List.of(singleSummaryItem(order.id(), orderTotalCents));
            }
            long qty = item.cantidad() != null && item.cantidad() > 0 ? item.cantidad() : 1L;
            long cents = product.unitAmountCents();
            BigDecimal unitPricePesos = BigDecimal.valueOf(cents)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            CfdiExpressPort.CfdiInvoiceLineItem line = new CfdiExpressPort.CfdiInvoiceLineItem(
                    item.productId(),
                    defaultProductCode,
                    defaultUnitCode,
                    product.name(),
                    qty,
                    unitPricePesos,
                    0.16d
            );
            raw.add(line);
            rawSumCents += cents * qty;

            if (!dedup.containsKey(item.productId())) {
                dedup.put(item.productId(), line);
                dedupSumCents += cents * qty;
            }
        }

        if (orderTotalCents > 0) {
            if (rawSumCents == orderTotalCents) {
                return raw;
            }
            if (raw.size() > dedup.size() && dedupSumCents == orderTotalCents) {
                return List.copyOf(dedup.values());
            }
            return List.of(singleSummaryItem(order.id(), orderTotalCents));
        }

        return raw;
    }

    private CfdiExpressPort.CfdiInvoiceLineItem singleSummaryItem(String orderId, long totalCents) {
        BigDecimal totalPesos = BigDecimal.valueOf(Math.max(0L, totalCents))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return new CfdiExpressPort.CfdiInvoiceLineItem(
                orderId,
                defaultProductCode,
                defaultUnitCode,
                "Compra en línea - Orden " + orderId,
                1L,
                totalPesos,
                0.16d
        );
    }

    private byte[] tryDownloadFileBytes(String url) {
        try {
            return cfdiExpressPort.downloadFileBytes(url);
        } catch (Exception ex) {
            log.warn("⚠️ [Facturación] No se pudo descargar archivo {}: {}", url, ex.getMessage());
            return null;
        }
    }

    private OrderInvoice buildDraftInvoice(
            OrderInvoice existing,
            Order order,
            String userId,
            InvoiceRequest request,
            InvoiceStatus status
    ) {
        String id = existing != null && existing.id() != null ? existing.id() : IdGenerator.invoiceId();
        LocalDateTime createdAt = existing != null && existing.createdAt() != null
                ? existing.createdAt() : LocalDateTime.now();

        return new OrderInvoice(
                id,
                order.id(),
                userId,
                request.rfc().trim().toUpperCase(),
                request.legalName().trim().toUpperCase(),
                request.zipCode().trim(),
                request.taxRegime().trim(),
                request.cfdiUse().trim().toUpperCase(),
                request.email().trim().toLowerCase(),
                resolveFormaPago(order),
                existing != null ? existing.cfdiExpressId() : null,
                existing != null ? existing.uuid() : null,
                status,
                existing != null ? existing.pdfUrl() : null,
                existing != null ? existing.xmlUrl() : null,
                null,
                existing != null ? existing.issuedAt() : null,
                createdAt
        );
    }

    private void ensureNotAlreadyStamped(OrderInvoice existing) {
        if (existing != null && existing.status() == InvoiceStatus.STAMPED) {
            throw new ArticleBusinessConflictException(
                    "Esta orden ya cuenta con una factura emitida (UUID: "
                            + nonBlank(existing.uuid(), existing.cfdiExpressId())
                            + "). Solo se permite emitir factura una vez por orden."
            );
        }
    }

    private String resolveFormaPago(Order order) {
        if (order != null && order.paymentUrl() != null) {
            String lower = order.paymentUrl().toLowerCase();
            if (lower.contains("bank_transfer") || lower.contains("instructions")) {
                return "03";
            }
        }
        return "04";
    }

    private User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(UserNotFoundException::new);
    }

    private Order requireOwnedOrder(User user, String orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new CfdiIntegrationException("No se encontró la orden solicitada: " + orderId));
        if (!user.id().equals(order.userId())) {
            throw new ArticleBusinessConflictException("No tienes permisos para facturar esta orden.");
        }
        return order;
    }

    private static String nonBlank(String primary, String fallback) {
        return (primary != null && !primary.isBlank()) ? primary : fallback;
    }

    public static InvoiceSummaryResponse toSummaryResponse(OrderInvoice invoice) {
        return toSummaryResponse(invoice, null);
    }

    public static InvoiceSummaryResponse toSummaryResponse(OrderInvoice invoice, Boolean emailSent) {
        if (invoice == null) {
            return null;
        }
        return new InvoiceSummaryResponse(
                invoice.id(),
                invoice.orderId(),
                invoice.status(),
                invoice.cfdiExpressId(),
                invoice.uuid(),
                invoice.rfc(),
                invoice.legalName(),
                invoice.zipCode(),
                invoice.taxRegime(),
                invoice.cfdiUse(),
                invoice.email(),
                invoice.formaPago(),
                invoice.pdfUrl() != null && !invoice.pdfUrl().isBlank(),
                invoice.xmlUrl() != null && !invoice.xmlUrl().isBlank(),
                invoice.errorMessage(),
                invoice.issuedAt(),
                invoice.createdAt(),
                emailSent
        );
    }
}
