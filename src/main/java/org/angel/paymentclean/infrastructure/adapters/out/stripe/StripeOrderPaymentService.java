package org.angel.paymentclean.infrastructure.adapters.out.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.StripeError;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentLink;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentListParams;
import com.stripe.param.PaymentLinkCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.checkout.SessionRetrieveParams;
import org.angel.paymentclean.domain.enums.Status;
import org.angel.paymentclean.domain.exception.StripeIntegrationException;
import org.angel.paymentclean.domain.model.ItemOrden;
import org.angel.paymentclean.domain.model.SpeiPaymentDetails;
import org.angel.paymentclean.domain.model.StripePaymentResult;
import org.angel.paymentclean.domain.port.out.StripePaymentPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class StripeOrderPaymentService implements StripePaymentPort {

    private static final Logger log = LoggerFactory.getLogger(StripeOrderPaymentService.class);

    private final StripeClient client;

    @Value("${app.frontend.url:http://localhost:4200}")
    private String frontendUrl = "http://localhost:4200";

    public StripeOrderPaymentService(StripeClient client) {
        this.client = client;
    }

    @Override
    public StripePaymentResult crearCheckoutSessionElementsParaOrden(
            String dbOrderId,
            String stripeCustomerId,
            List<ItemOrden> items
    ) {
        try {
            Map<String, String> metadata = Map.of("order_id", dbOrderId, "payment_method", "elements");
            SessionCreateParams.Builder builder = buildElementsSessionBuilder(dbOrderId, stripeCustomerId, metadata, items)
                    .addAllowedPaymentMethodType(SessionCreateParams.AllowedPaymentMethodType.CARD)
                    .addAllowedPaymentMethodType(SessionCreateParams.AllowedPaymentMethodType.CUSTOMER_BALANCE)
                    .setPaymentMethodOptions(buildCustomerBalancePaymentMethodOptions());

            Session session = client.v1().checkout().sessions().create(builder.build());
            return toSessionResult(session);
        } catch (StripeException e) {
            throw toStripeException("crear Checkout Session (Elements)", e);
        }
    }

    @Override
    public StripePaymentResult crearCheckoutSessionSpeiParaOrden(
            String dbOrderId,
            String stripeCustomerId,
            String customerEmail,
            List<ItemOrden> items
    ) {
        try {
            Map<String, String> metadata = new HashMap<>();
            metadata.put("order_id", dbOrderId);
            metadata.put("payment_method", "spei");
            if (customerEmail != null && !customerEmail.isBlank()) {
                metadata.put("recipient_email", customerEmail.trim());
            }

            SessionCreateParams.Builder builder = buildElementsSessionBuilder(dbOrderId, stripeCustomerId, metadata, items)
                    .addAllowedPaymentMethodType(SessionCreateParams.AllowedPaymentMethodType.CUSTOMER_BALANCE)
                    .setPaymentMethodOptions(buildCustomerBalancePaymentMethodOptions());

            RequestOptions requestOptions = RequestOptions.builder()
                    .setIdempotencyKey("create-spei-session-" + dbOrderId)
                    .build();

            Session session = client.v1().checkout().sessions().create(builder.build(), requestOptions);
            return toSessionResult(session);
        } catch (StripeException e) {
            throw toStripeException("crear Checkout Session SPEI (Elements)", e);
        }
    }

    @Override
    public StripePaymentResult recuperarCheckoutSession(String checkoutSessionId) {
        try {
            SessionRetrieveParams retrieveParams = SessionRetrieveParams.builder()
                    .addExpand("payment_intent")
                    .build();
            Session session = client.v1().checkout().sessions().retrieve(checkoutSessionId, retrieveParams);
            return toSessionResult(session);
        } catch (StripeException e) {
            throw toStripeException("recuperar Checkout Session", e);
        }
    }

    private SessionCreateParams.Builder buildElementsSessionBuilder(
            String dbOrderId,
            String stripeCustomerId,
            Map<String, String> metadata,
            List<ItemOrden> items
    ) {
        SessionCreateParams.PaymentIntentData.Builder piDataBuilder = SessionCreateParams.PaymentIntentData.builder()
                .putAllMetadata(metadata);

        SessionCreateParams.Builder builder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setUiMode(SessionCreateParams.UiMode.ELEMENTS)
                .setCustomer(stripeCustomerId)
                .setClientReferenceId(dbOrderId)
                .setReturnUrl(resolvedFrontendUrl() + "/cart/resultado?session_id={CHECKOUT_SESSION_ID}&order_id=" + dbOrderId)
                .putAllMetadata(metadata)
                .setPaymentIntentData(piDataBuilder.build());

        appendLineItems(builder, items);
        return builder;
    }

    private SessionCreateParams.PaymentMethodOptions buildCustomerBalancePaymentMethodOptions() {
        return SessionCreateParams.PaymentMethodOptions.builder()
                .setCustomerBalance(
                        SessionCreateParams.PaymentMethodOptions.CustomerBalance.builder()
                                .setFundingType(
                                        SessionCreateParams.PaymentMethodOptions.CustomerBalance.FundingType.BANK_TRANSFER
                                )
                                .setBankTransfer(
                                        SessionCreateParams.PaymentMethodOptions.CustomerBalance.BankTransfer.builder()
                                                .setType(
                                                        SessionCreateParams.PaymentMethodOptions.CustomerBalance.BankTransfer.Type.MX_BANK_TRANSFER
                                                )
                                                .addRequestedAddressType(
                                                        SessionCreateParams.PaymentMethodOptions.CustomerBalance.BankTransfer.RequestedAddressType.SPEI
                                                )
                                                .build()
                                )
                                .build()
                )
                .build();
    }

    @Override
    public StripePaymentResult crearPaymentIntentParaOrden(String dbOrderId, String stripeCustomerId, Long totalEnCentavos) {

        try{
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(totalEnCentavos)
                    .setCurrency("mxn")
                    .setCustomer(stripeCustomerId)
                    .setDescription("Orden #" + dbOrderId + " - Tienda de Tecnología")
                    .putAllMetadata(Map.of("order_id", dbOrderId, "payment_method", "card"))
                    .addAllowedPaymentMethodType(PaymentIntentCreateParams.AllowedPaymentMethodType.CARD)
                    .build();

            RequestOptions requestOptions = RequestOptions.builder()
                    .setIdempotencyKey("create-pi-order-" + dbOrderId)
                    .build();

            PaymentIntent pi = client.v1().paymentIntents().create(params, requestOptions);
            return toPaymentResult(pi);

        } catch (StripeException e) {
            throw toStripeException("crear PaymentIntent", e);
        }
    }

    @Override
    public StripePaymentResult recuperarPaymentIntent(String paymentIntentId) {
        try {
            PaymentIntent pi = client.v1().paymentIntents().retrieve(paymentIntentId);
            return toPaymentResult(pi);
        } catch (StripeException e) {
            throw toStripeException("recuperar PaymentIntent", e);
        }
    }

    @Override
    public Optional<String> resolvePaidPaymentIntentId(
            String checkoutSessionId,
            String paymentIntentId,
            String expectedOrderId
    ) {
        if (checkoutSessionId != null && !checkoutSessionId.isBlank()) {
            try {
                SessionRetrieveParams retrieveParams = SessionRetrieveParams.builder()
                        .addExpand("payment_intent")
                        .build();
                Session session = client.v1().checkout().sessions().retrieve(checkoutSessionId.trim(), retrieveParams);
                if (session != null && matchesExpectedOrder(session, expectedOrderId)) {
                    PaymentIntent expandedPi = session.getPaymentIntentObject();
                    boolean sessionPaid = "paid".equalsIgnoreCase(session.getPaymentStatus())
                            || (expandedPi != null && "succeeded".equalsIgnoreCase(expandedPi.getStatus()));
                    if (sessionPaid) {
                        String piId = session.getPaymentIntent();
                        if ((piId == null || piId.isBlank()) && expandedPi != null) {
                            piId = expandedPi.getId();
                        }
                        return Optional.of(piId != null && !piId.isBlank() ? piId : session.getId());
                    }
                }
            } catch (StripeException e) {
                log.warn("No se pudo verificar Checkout Session {} para orden {}: {}",
                        checkoutSessionId, expectedOrderId, e.getMessage());
            }
        }

        if (paymentIntentId != null && !paymentIntentId.isBlank()) {
            try {
                PaymentIntent pi = client.v1().paymentIntents().retrieve(paymentIntentId.trim());
                if (pi != null && "succeeded".equalsIgnoreCase(pi.getStatus())) {
                    String metaOrderId = pi.getMetadata() != null ? pi.getMetadata().get("order_id") : null;
                    if (expectedOrderId == null || metaOrderId == null || expectedOrderId.equals(metaOrderId)) {
                        return Optional.of(pi.getId());
                    }
                }
            } catch (StripeException e) {
                log.warn("No se pudo verificar PaymentIntent {} para orden {}: {}",
                        paymentIntentId, expectedOrderId, e.getMessage());
            }
        }

        return Optional.empty();
    }

    private boolean matchesExpectedOrder(Session session, String expectedOrderId) {
        if (expectedOrderId == null || expectedOrderId.isBlank()) {
            return true;
        }
        String clientRef = session.getClientReferenceId();
        if (clientRef != null && !clientRef.isBlank() && !expectedOrderId.equals(clientRef)) {
            return false;
        }
        String metaOrderId = session.getMetadata() != null ? session.getMetadata().get("order_id") : null;
        return metaOrderId == null || metaOrderId.isBlank() || expectedOrderId.equals(metaOrderId);
    }

    @Override
    public void cancelarPaymentIntent(String paymentIntentId) {
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            return;
        }
        try {
            client.v1().paymentIntents().cancel(paymentIntentId);
        } catch (StripeException e) {
            log.warn("No se pudo cancelar PaymentIntent {} en Stripe (puede estar ya pagado o cancelado): {}",
                    paymentIntentId, e.getMessage());
        }
    }

    @Override
    public void cancelarCheckoutSession(String checkoutSessionId) {
        if (checkoutSessionId == null || checkoutSessionId.isBlank()) {
            return;
        }
        try {
            client.v1().checkout().sessions().expire(checkoutSessionId);
        } catch (StripeException e) {
            log.warn("No se pudo expirar Checkout Session {} en Stripe (puede estar ya pagada o expirada): {}",
                    checkoutSessionId, e.getMessage());
        }
    }

    @Override
    public StripePaymentResult crearLinkDePagoParaOrdenPendiente(String dbOrderId, String stripeCustomerId, List<ItemOrden> items) {
        try {
            String baseFrontendUrl = resolvedFrontendUrl();
            SessionCreateParams.Builder builder = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setCustomer(stripeCustomerId)
                    .setClientReferenceId(dbOrderId)
                    .setSuccessUrl(baseFrontendUrl + "/cart/resultado?session_id={CHECKOUT_SESSION_ID}&order_id=" + dbOrderId)
                    .setCancelUrl(baseFrontendUrl + "/orders")
                    .putAllMetadata(Map.of("order_id", dbOrderId, "payment_method", "hosted_link"))
                    .addAllowedPaymentMethodType(SessionCreateParams.AllowedPaymentMethodType.CARD)
                    .addAllowedPaymentMethodType(SessionCreateParams.AllowedPaymentMethodType.CUSTOMER_BALANCE)
                    .setPaymentMethodOptions(buildCustomerBalancePaymentMethodOptions())
                    .setPaymentIntentData(
                            SessionCreateParams.PaymentIntentData.builder()
                                    .putMetadata("order_id", dbOrderId)
                                    .build()
                    );

            appendLineItems(builder, items);

            Session session = client.v1().checkout().sessions().create(builder.build());
            return toSessionResult(session);
        } catch (StripeException e) {
            throw toStripeException("crear Checkout Session", e);
        }
    }

    private static final List<String> ALLOWED_TUNNEL_SUFFIXES = List.of(
            ".trycloudflare.com",
            ".lhr.life",
            ".localhost.run",
            ".ngrok-free.app",
            ".ngrok-free.dev",
            ".ngrok.io",
            ".devtunnels.ms",
            ".loca.lt"
    );

    String resolvedFrontendUrl() {
        String configuredBase = normalizeConfiguredFrontendUrl();
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            HttpServletRequest request = servletAttrs.getRequest();
            String requestOrigin = resolveOriginFromRequest(request, configuredBase);
            if (requestOrigin != null) {
                return requestOrigin;
            }
        }
        return configuredBase;
    }

    private String normalizeConfiguredFrontendUrl() {
        String base = (frontendUrl == null || frontendUrl.isBlank())
                ? "http://localhost:4200"
                : frontendUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private String resolveOriginFromRequest(HttpServletRequest request, String configuredBase) {
        if (request == null) {
            return null;
        }
        String originCandidate = normalizeOrigin(request.getHeader("Origin"));
        if (originCandidate != null && isAllowedFrontendOrigin(originCandidate, configuredBase)) {
            return originCandidate;
        }

        String forwardedHost = firstHeaderValue(request.getHeader("X-Forwarded-Host"));
        if (forwardedHost != null) {
            String forwardedProto = firstHeaderValue(request.getHeader("X-Forwarded-Proto"));
            String scheme = (forwardedProto != null && ("https".equalsIgnoreCase(forwardedProto) || "http".equalsIgnoreCase(forwardedProto)))
                    ? forwardedProto.toLowerCase(Locale.ROOT)
                    : (forwardedHost.startsWith("localhost") || forwardedHost.startsWith("127.0.0.1") ? "http" : "https");
            String forwardedOrigin = normalizeOrigin(scheme + "://" + forwardedHost);
            if (forwardedOrigin != null && isAllowedFrontendOrigin(forwardedOrigin, configuredBase)) {
                return forwardedOrigin;
            }
        }

        String refererCandidate = normalizeOrigin(request.getHeader("Referer"));
        if (refererCandidate != null && isAllowedFrontendOrigin(refererCandidate, configuredBase)) {
            return refererCandidate;
        }
        return null;
    }

    private String firstHeaderValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String first = raw.split(",")[0].trim();
        return first.isEmpty() ? null : first;
    }

    private String normalizeOrigin(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(rawUrl.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.isBlank()) {
                return null;
            }
            String lowerScheme = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(lowerScheme) && !"https".equals(lowerScheme)) {
                return null;
            }
            int port = uri.getPort();
            return port > 0
                    ? lowerScheme + "://" + host.toLowerCase(Locale.ROOT) + ":" + port
                    : lowerScheme + "://" + host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isAllowedFrontendOrigin(String origin, String configuredBase) {
        if (origin.equalsIgnoreCase(configuredBase)) {
            return true;
        }
        URI uri = URI.create(origin);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if ("localhost".equals(host) || "127.0.0.1".equals(host)) {
            return true;
        }
        if ("https".equals(scheme)) {
            return ALLOWED_TUNNEL_SUFFIXES.stream().anyMatch(host::endsWith);
        }
        return false;
    }

    private void appendLineItems(SessionCreateParams.Builder builder, List<ItemOrden> items) {
        if (items == null) {
            return;
        }
        for (ItemOrden item : items) {
            builder.addLineItem(
                    SessionCreateParams.LineItem.builder()
                            .setPrice(item.stripePriceId())
                            .setQuantity(item.cantidad())
                            .build()
            );
        }
    }

    private StripePaymentResult toSessionResult(Session session) {
        String effectiveStatus;
        if ("paid".equalsIgnoreCase(session.getPaymentStatus())) {
            effectiveStatus = "paid";
        } else if ("complete".equalsIgnoreCase(session.getStatus())) {
            // Para métodos asíncronos como SPEI, Stripe marca status=complete con payment_status=unpaid.
            effectiveStatus = "requires_action";
        } else {
            effectiveStatus = session.getStatus();
        }
        SpeiPaymentDetails speiDetails = extractSpeiDetails(session.getPaymentIntentObject());
        String resolvedUrl = (speiDetails != null && speiDetails.hostedInstructionsUrl() != null)
                ? speiDetails.hostedInstructionsUrl()
                : session.getUrl();
        return new StripePaymentResult(
                session.getId(),
                session.getClientSecret(),
                resolvedUrl,
                mapStripeStatus(effectiveStatus),
                speiDetails
        );
    }

    @Override
    public String crearPaymentLinkDeCatalogo(String stripePriceId, long cantidad, String dbOrderId) {
        try {
            PaymentLinkCreateParams params = PaymentLinkCreateParams.builder()
                    .addLineItem(
                            PaymentLinkCreateParams.LineItem.builder()
                                    .setPrice(stripePriceId)
                                    .setQuantity(cantidad)
                                    .build()
                    )
                    .putAllMetadata(Map.of("order_id", dbOrderId))
                    .setAfterCompletion(
                            PaymentLinkCreateParams.AfterCompletion.builder()
                                    .setType(PaymentLinkCreateParams.AfterCompletion.Type.REDIRECT)
                                    .setRedirect(
                                            PaymentLinkCreateParams.AfterCompletion.Redirect.builder()
                                                    .setUrl(resolvedFrontendUrl() + "/cart/resultado?order_id=" + dbOrderId)
                                                    .build()
                                    )
                                    .build()
                    )
                    .build();

            PaymentLink paymentLink = client.v1().paymentLinks().create(params);
            return paymentLink.getUrl();
        } catch (StripeException e) {
            throw new RuntimeException("Error al crear PaymentLink: " + e.getMessage(), e);
        }
    }

    @Override
    public List<StripePaymentResult> listarPagosPorCliente(String stripeCustomerId) {
        try {
            PaymentIntentListParams listParams = PaymentIntentListParams.builder()
                    .setCustomer(stripeCustomerId)
                    .setLimit(50L)
                    .build();

            return client.v1().paymentIntents().list(listParams).getData().stream()
                    .map(pi -> new StripePaymentResult(pi.getId(), pi.getClientSecret(), null, mapStripeStatus(pi.getStatus())))
                    .toList();
        } catch (StripeException e) {
            throw new RuntimeException("Error al listar pagos del cliente: " + e.getMessage(), e);
        }
    }

    private StripePaymentResult toPaymentResult(PaymentIntent pi) {
        SpeiPaymentDetails speiDetails = extractSpeiDetails(pi);
        String hostedInstructionsUrl = speiDetails != null ? speiDetails.hostedInstructionsUrl() : null;
        return new StripePaymentResult(
                pi.getId(),
                pi.getClientSecret(),
                hostedInstructionsUrl,
                mapStripeStatus(pi.getStatus()),
                speiDetails
        );
    }

    private SpeiPaymentDetails extractSpeiDetails(PaymentIntent pi) {
        if (pi == null || pi.getNextAction() == null
                || pi.getNextAction().getDisplayBankTransferInstructions() == null) {
            return null;
        }

        PaymentIntent.NextAction.DisplayBankTransferInstructions instructions =
                pi.getNextAction().getDisplayBankTransferInstructions();

        PaymentIntent.NextAction.DisplayBankTransferInstructions.FinancialAddress.Spei spei = null;
        if (instructions.getFinancialAddresses() != null) {
            for (PaymentIntent.NextAction.DisplayBankTransferInstructions.FinancialAddress address :
                    instructions.getFinancialAddresses()) {
                if (address.getSpei() != null) {
                    spei = address.getSpei();
                    break;
                }
            }
        }

        if (spei == null && instructions.getHostedInstructionsUrl() == null) {
            return null;
        }

        return new SpeiPaymentDetails(
                spei != null ? spei.getClabe() : null,
                spei != null ? spei.getBankName() : null,
                spei != null ? spei.getBankCode() : null,
                instructions.getReference(),
                instructions.getHostedInstructionsUrl(),
                instructions.getAmountRemaining() != null ? instructions.getAmountRemaining() : pi.getAmount(),
                instructions.getCurrency() != null ? instructions.getCurrency() : pi.getCurrency()
        );
    }

    private Status mapStripeStatus(String stripeStatus) {
        if (stripeStatus == null) {
            return Status.PENDING;
        }
        return switch (stripeStatus.toLowerCase()) {
            case "succeeded", "paid", "complete" -> Status.PAID;
            case "requires_payment_method", "requires_confirmation",
                 "requires_action", "processing", "open" -> Status.PENDING;
            case "canceled", "expired" -> Status.CANCELED;
            default -> Status.FAILED;
        };
    }

    private StripeIntegrationException toStripeException(String operation, StripeException e) {
        StripeError err = e.getStripeError();
        String code = e.getCode() != null ? e.getCode() : (err != null ? err.getCode() : null);
        String type = err != null ? err.getType() : null;
        String declineCode = err != null ? err.getDeclineCode() : null;
        String param = err != null ? err.getParam() : null;
        String detail = (err != null && err.getMessage() != null && !err.getMessage().isBlank())
                ? err.getMessage()
                : e.getMessage();
        log.error("❌ StripeException al {}: status={}, requestId={}, code={}, type={}, declineCode={}, param={}, message={}",
                operation, e.getStatusCode(), e.getRequestId(), code, type, declineCode, param, detail, e);
        return new StripeIntegrationException("Error al " + operation + ": " + detail, e);
    }
}
