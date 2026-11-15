package org.angel.paymentclean.infrastructure.adapters.in.webhook;

import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import org.angel.paymentclean.domain.enums.Status;
import org.angel.paymentclean.domain.port.in.ProcessPaymentWebhookUseCase;
import org.angel.paymentclean.infrastructure.adapters.out.persistence.repository.DataProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StripeWebhookHandlerTest {

    @Mock
    private ProcessPaymentWebhookUseCase webhookUseCase;

    @Mock
    private DataProcessedEventRepository processedEventRepository;

    private String signPayload(String secret, String payload) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String signedPayload = timestamp + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        return "t=" + timestamp + ",v1=" + signature;
    }

    @Test
    void procesarEventoStripe_paymentIntentSucceededWith2026ApiVersion_shouldMarkOrderAsPaid() throws Exception {
        String secret = "whsec_test_secret_123";
        StripeWebhookHandler handler = new StripeWebhookHandler(webhookUseCase, processedEventRepository);
        var field = StripeWebhookHandler.class.getDeclaredField("endpointSecret");
        field.setAccessible(true);
        field.set(handler, secret);

        String payload = """
                {
                  "id": "evt_test_2026",
                  "object": "event",
                  "api_version": "2026-09-30.endive",
                  "type": "payment_intent.succeeded",
                  "data": {
                    "object": {
                      "id": "pi_3UbCXfBdjYWPYXgR0eDoB3z6",
                      "object": "payment_intent",
                      "amount": 140000,
                      "currency": "mxn",
                      "customer": "cus_VcIOH9HfBArvw1",
                      "status": "succeeded",
                      "metadata": {
                        "order_id": "ord_zcl9jp39zutrwlwi"
                      }
                    }
                  }
                }
                """;

        long timestamp = Instant.now().getEpochSecond();
        String signedPayload = timestamp + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        String sigHeader = "t=" + timestamp + ",v1=" + signature;

        when(processedEventRepository.existsById("evt_test_2026")).thenReturn(false);

        var response = handler.procesarEventoStripe(payload, sigHeader);

        assertEquals(200, response.getStatusCode().value());
        verify(webhookUseCase).markOrderAsPaid(
                "ord_zcl9jp39zutrwlwi",
                Status.PAID,
                "pi_3UbCXfBdjYWPYXgR0eDoB3z6",
                "cus_VcIOH9HfBArvw1"
        );
    }

    @Test
    void procesarEventoStripe_checkoutSessionCompletedUnpaidSpei_shouldLinkPaymentIntentWithoutMarkingPaid() throws Exception {
        String secret = "whsec_test_secret_123";
        StripeWebhookHandler handler = new StripeWebhookHandler(webhookUseCase, processedEventRepository);
        var field = StripeWebhookHandler.class.getDeclaredField("endpointSecret");
        field.setAccessible(true);
        field.set(handler, secret);

        String payload = """
                {
                  "id": "evt_test_spei_unpaid",
                  "object": "event",
                  "api_version": "2026-09-30.endive",
                  "type": "checkout.session.completed",
                  "data": {
                    "object": {
                      "id": "cs_test_spei_1",
                      "object": "checkout.session",
                      "client_reference_id": "ord_spei_1",
                      "customer": "cus_spei_1",
                      "payment_intent": "pi_spei_1",
                      "payment_status": "unpaid",
                      "status": "complete"
                    }
                  }
                }
                """;

        when(processedEventRepository.existsById("evt_test_spei_unpaid")).thenReturn(false);

        var response = handler.procesarEventoStripe(payload, signPayload(secret, payload));

        assertEquals(200, response.getStatusCode().value());
        verify(webhookUseCase).linkPaymentIntentToOrder("ord_spei_1", "pi_spei_1");
        verify(webhookUseCase, never()).markOrderAsPaid(any(), any(), any(), any());
    }

    @Test
    void procesarEventoStripe_checkoutSessionAsyncPaymentSucceeded_shouldMarkOrderAsPaid() throws Exception {
        String secret = "whsec_test_secret_123";
        StripeWebhookHandler handler = new StripeWebhookHandler(webhookUseCase, processedEventRepository);
        var field = StripeWebhookHandler.class.getDeclaredField("endpointSecret");
        field.setAccessible(true);
        field.set(handler, secret);

        String payload = """
                {
                  "id": "evt_test_spei_async_ok",
                  "object": "event",
                  "api_version": "2026-09-30.endive",
                  "type": "checkout.session.async_payment_succeeded",
                  "data": {
                    "object": {
                      "id": "cs_test_spei_2",
                      "object": "checkout.session",
                      "client_reference_id": "ord_spei_2",
                      "customer": "cus_spei_2",
                      "payment_intent": "pi_spei_2",
                      "payment_status": "paid",
                      "status": "complete"
                    }
                  }
                }
                """;

        when(processedEventRepository.existsById("evt_test_spei_async_ok")).thenReturn(false);

        var response = handler.procesarEventoStripe(payload, signPayload(secret, payload));

        assertEquals(200, response.getStatusCode().value());
        verify(webhookUseCase).markOrderAsPaid("ord_spei_2", Status.PAID, "pi_spei_2", "cus_spei_2");
    }

    @Test
    void procesarEventoStripe_paymentIntentCanceled_shouldMarkOrderAsCanceled() throws Exception {
        String secret = "whsec_test_secret_123";
        StripeWebhookHandler handler = new StripeWebhookHandler(webhookUseCase, processedEventRepository);
        var field = StripeWebhookHandler.class.getDeclaredField("endpointSecret");
        field.setAccessible(true);
        field.set(handler, secret);

        String payload = """
                {
                  "id": "evt_test_pi_canceled",
                  "object": "event",
                  "api_version": "2026-09-30.endive",
                  "type": "payment_intent.canceled",
                  "data": {
                    "object": {
                      "id": "pi_canceled_1",
                      "object": "payment_intent",
                      "cancellation_reason": "abandoned",
                      "status": "canceled",
                      "metadata": {
                        "order_id": "ord_canceled_1"
                      }
                    }
                  }
                }
                """;

        when(processedEventRepository.existsById("evt_test_pi_canceled")).thenReturn(false);

        var response = handler.procesarEventoStripe(payload, signPayload(secret, payload));

        assertEquals(200, response.getStatusCode().value());
        verify(webhookUseCase).markOrderAsCanceled("ord_canceled_1", "pi_canceled_1", "abandoned");
    }
}

