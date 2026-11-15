package org.angel.paymentclean.infrastructure.adapters.in.webhook;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import org.angel.paymentclean.domain.enums.Status;
import org.angel.paymentclean.domain.port.in.ProcessPaymentWebhookUseCase;
import org.angel.paymentclean.infrastructure.adapters.out.persistence.entity.ProcessedEventEntity;
import org.angel.paymentclean.infrastructure.adapters.out.persistence.repository.DataProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/webhooks/stripe")
public class StripeWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookHandler.class);

    private final ProcessPaymentWebhookUseCase webhookUseCase;
    private final DataProcessedEventRepository processedEventRepository;

    @Value("${stripe.webhook.secret}")
    private String endpointSecret;

    public StripeWebhookHandler(
            ProcessPaymentWebhookUseCase webhookUseCase,
            DataProcessedEventRepository processedEventRepository) {
        this.webhookUseCase = webhookUseCase;
        this.processedEventRepository = processedEventRepository;
    }

    @PostMapping
    public ResponseEntity<String> procesarEventoStripe(
            @RequestBody String payloadJson,
            @RequestHeader(value = "Stripe-Signature", required = false) String stripeSignatureHeader) {

        log.info("🔔 Webhook HTTP recibido en /api/webhooks/stripe");

        if (stripeSignatureHeader == null) {
            log.error("❌ Encabezado 'Stripe-Signature' ausente");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Firma ausente");
        }

        final Event event;
        try {
            event = Webhook.constructEvent(payloadJson, stripeSignatureHeader, endpointSecret);
        } catch (SignatureVerificationException e) {
            log.error(
                    "❌ Firma de Webhook inválida. Verifica que stripe.webhook.secret en .env coincida con el secreto de Stripe CLI. Error: {}",
                    e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Firma inválida");
        }

        // 🛡️ Capa 1 de Idempotencia: Verificar si este evento exacto ya fue procesado
        // antes
        if (processedEventRepository.existsById(event.getId())) {
            log.info("🛡️ [Idempotencia Evento] Evento '{}' ({}) ya fue procesado con anterioridad. Ignorando reenvío.",
                    event.getId(), event.getType());
            return ResponseEntity.ok("Evento ya procesado previamente");
        }

        log.info("⚡ Evento Stripe verificado exitosamente: {} [ID: {}]", event.getType(), event.getId());

        switch (event.getType()) {
            case "checkout.session.completed" -> {
                Session session = deserializeSession(event);
                if (session != null) {
                    String dbOrderId = resolveOrderIdFromSession(session);
                    String stripeCustomerId = session.getCustomer();
                    String paymentIntentId = session.getPaymentIntent();
                    String paymentStatus = session.getPaymentStatus();

                    log.info("📦 Checkout Session completada para orden ID: {}, customer: {}, pi: {}, payment_status: {}",
                            dbOrderId, stripeCustomerId, paymentIntentId, paymentStatus);

                    if (dbOrderId != null) {
                        if ("unpaid".equalsIgnoreCase(paymentStatus)) {
                            webhookUseCase.linkPaymentIntentToOrder(dbOrderId, paymentIntentId);
                        } else {
                            webhookUseCase.markOrderAsPaid(
                                    dbOrderId,
                                    Status.PAID,
                                    paymentIntentId,
                                    stripeCustomerId);
                        }
                    }
                }
            }
            case "checkout.session.async_payment_succeeded" -> {
                Session session = deserializeSession(event);
                if (session != null) {
                    String dbOrderId = resolveOrderIdFromSession(session);
                    String stripeCustomerId = session.getCustomer();
                    String paymentIntentId = session.getPaymentIntent();

                    log.info("🏦 Pago asíncrono (SPEI) acreditado en Checkout Session para orden ID: {}, pi: {}",
                            dbOrderId, paymentIntentId);

                    if (dbOrderId != null || paymentIntentId != null) {
                        webhookUseCase.markOrderAsPaid(
                                dbOrderId,
                                Status.PAID,
                                paymentIntentId,
                                stripeCustomerId);
                    }
                }
            }
            case "checkout.session.async_payment_failed" -> {
                Session session = deserializeSession(event);
                if (session != null) {
                    String dbOrderId = resolveOrderIdFromSession(session);
                    log.warn("❌ Pago asíncrono (SPEI) fallido en Checkout Session para orden ID: {}", dbOrderId);
                    if (dbOrderId != null) {
                        webhookUseCase.markOrderAsPaymentFailed(dbOrderId, Status.FAILED, "Pago asíncrono SPEI fallido");
                    }
                }
            }
            case "checkout.session.expired" -> {
                Session session = deserializeSession(event);
                if (session != null) {
                    String dbOrderId = resolveOrderIdFromSession(session);
                    String paymentIntentId = session.getPaymentIntent();
                    log.info("⏰ Checkout Session expirada para orden ID: {}, pi: {}", dbOrderId, paymentIntentId);
                    if (dbOrderId != null || paymentIntentId != null) {
                        webhookUseCase.markOrderAsCanceled(dbOrderId, paymentIntentId, "Checkout Session expirada en Stripe");
                    }
                }
            }
            case "payment_intent.succeeded" -> {
                PaymentIntent paymentIntent = deserializePaymentIntent(event);
                if (paymentIntent != null) {
                    String dbOrderId = paymentIntent.getMetadata() != null ? paymentIntent.getMetadata().get("order_id")
                            : null;
                    String stripeCustomerId = paymentIntent.getCustomer();

                    log.info("💳 PaymentIntent exitoso para orden ID: {}, pi: {}", dbOrderId, paymentIntent.getId());

                    if (dbOrderId != null || paymentIntent.getId() != null) {
                        webhookUseCase.markOrderAsPaid(
                                dbOrderId,
                                Status.PAID,
                                paymentIntent.getId(),
                                stripeCustomerId);
                    }
                }
            }
            case "payment_intent.payment_failed" -> {
                PaymentIntent paymentIntent = deserializePaymentIntent(event);
                if (paymentIntent != null) {
                    String dbOrderId = paymentIntent.getMetadata() != null ? paymentIntent.getMetadata().get("order_id")
                            : null;
                    var lastErr = paymentIntent.getLastPaymentError();
                    String errorMsg = lastErr != null ? lastErr.getMessage() : "Pago fallido";
                    String errorCode = lastErr != null ? lastErr.getCode() : null;
                    String declineCode = lastErr != null ? lastErr.getDeclineCode() : null;
                    String errorType = lastErr != null ? lastErr.getType() : null;

                    log.warn("❌ PaymentIntent fallido para orden ID: {}, pi: {}, code={}, declineCode={}, type={}. Causa: {}",
                            dbOrderId, paymentIntent.getId(), errorCode, declineCode, errorType, errorMsg);

                    if (dbOrderId != null || paymentIntent.getId() != null) {
                        webhookUseCase.markOrderAsPaymentFailed(dbOrderId, paymentIntent.getId(), Status.FAILED, errorMsg);
                    }
                }
            }
            case "payment_intent.canceled" -> {
                PaymentIntent paymentIntent = deserializePaymentIntent(event);
                if (paymentIntent != null) {
                    String dbOrderId = paymentIntent.getMetadata() != null ? paymentIntent.getMetadata().get("order_id")
                            : null;
                    String reason = paymentIntent.getCancellationReason() != null
                            ? paymentIntent.getCancellationReason()
                            : "PaymentIntent cancelado en Stripe";

                    log.info("🚫 PaymentIntent cancelado para orden ID: {}, pi: {}", dbOrderId, paymentIntent.getId());

                    if (dbOrderId != null || paymentIntent.getId() != null) {
                        webhookUseCase.markOrderAsCanceled(dbOrderId, paymentIntent.getId(), reason);
                    }
                }
            }
            default -> {
                log.info("ℹ️ Evento recibido pero sin acción requerida: {}", event.getType());
            }
        }

        // Registrar el evento como procesado para garantizar idempotencia futura
        try {
            processedEventRepository
                    .save(new ProcessedEventEntity(event.getId(), event.getType(), LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("⚠️ No se pudo registrar el evento procesado {} en BD: {}", event.getId(), e.getMessage());
        }

        return ResponseEntity.ok("Evento recibido");
    }

    private Session deserializeSession(Event event) {
        return (Session) event.getDataObjectDeserializer()
                .getObject()
                .orElseGet(() -> {
                    try {
                        return (Session) event.getDataObjectDeserializer().deserializeUnsafe();
                    } catch (Exception e) {
                        return null;
                    }
                });
    }

    private PaymentIntent deserializePaymentIntent(Event event) {
        return (PaymentIntent) event.getDataObjectDeserializer()
                .getObject()
                .orElseGet(() -> {
                    try {
                        return (PaymentIntent) event.getDataObjectDeserializer().deserializeUnsafe();
                    } catch (Exception e) {
                        return null;
                    }
                });
    }

    private String resolveOrderIdFromSession(Session session) {
        String dbOrderId = session.getClientReferenceId();
        if (dbOrderId == null && session.getMetadata() != null) {
            dbOrderId = session.getMetadata().get("order_id");
        }
        return dbOrderId;
    }
}
