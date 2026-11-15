package org.angel.paymentclean.domain.port.out;

import org.angel.paymentclean.domain.model.ItemOrden;
import org.angel.paymentclean.domain.model.StripePaymentResult;

import java.util.List;

public interface StripePaymentPort {
    StripePaymentResult crearCheckoutSessionElementsParaOrden(String dbOrderId, String stripeCustomerId, List<ItemOrden> items);
    StripePaymentResult crearCheckoutSessionSpeiParaOrden(String dbOrderId, String stripeCustomerId, String customerEmail, List<ItemOrden> items);
    StripePaymentResult recuperarCheckoutSession(String checkoutSessionId);
    StripePaymentResult crearPaymentIntentParaOrden(String dbOrderId, String stripeCustomerId, Long totalEnCentavos);
    StripePaymentResult recuperarPaymentIntent(String paymentIntentId);
    void cancelarPaymentIntent(String paymentIntentId);
    void cancelarCheckoutSession(String checkoutSessionId);
    StripePaymentResult crearLinkDePagoParaOrdenPendiente(String dbOrderId, String stripeCustomerId, List<ItemOrden> items);
    String crearPaymentLinkDeCatalogo(String stripePriceId, long cantidad, String dbOrderId);
    List<StripePaymentResult> listarPagosPorCliente(String stripeCustomerId);
}
