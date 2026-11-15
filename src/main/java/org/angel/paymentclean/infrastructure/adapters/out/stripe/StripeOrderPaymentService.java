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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private String resolvedFrontendUrl() {
        String base = (frontendUrl == null || frontendUrl.isBlank())
                ? "http://localhost:4200"
                : frontendUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
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
