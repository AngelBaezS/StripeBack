package org.angel.paymentclean.domain.port.in;

import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.InvoiceRequest;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.InvoiceSummaryResponse;

import java.util.Optional;

public interface InvoiceUseCase {
    InvoiceSummaryResponse saveCheckoutInvoiceRequest(String username, String orderId, InvoiceRequest request);
    InvoiceSummaryResponse issueInvoiceForOrder(String username, String orderId, InvoiceRequest request);
    InvoiceSummaryResponse resendInvoiceEmail(String username, String orderId, String overrideEmail);
    boolean hasPendingInvoiceRequest(String orderId);
    void processAutomaticInvoiceOnPaymentConfirmed(String orderId);
    Optional<InvoiceSummaryResponse> getInvoiceForOrder(String username, String orderId);
    byte[] downloadInvoicePdf(String username, String orderId);
    byte[] downloadInvoiceXml(String username, String orderId);
}
