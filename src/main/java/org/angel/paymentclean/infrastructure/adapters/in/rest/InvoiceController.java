package org.angel.paymentclean.infrastructure.adapters.in.rest;

import jakarta.validation.Valid;
import org.angel.paymentclean.domain.port.in.InvoiceUseCase;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.InvoiceRequest;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.InvoiceSummaryResponse;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.ResendInvoiceEmailRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/invoices")
public class InvoiceController {

    private final InvoiceUseCase invoiceUseCase;

    public InvoiceController(InvoiceUseCase invoiceUseCase) {
        this.invoiceUseCase = invoiceUseCase;
    }

    @PostMapping("/order/{orderId}")
    public ResponseEntity<InvoiceSummaryResponse> saveCheckoutInvoiceRequest(
            @PathVariable String orderId,
            @Valid @RequestBody InvoiceRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(
                invoiceUseCase.saveCheckoutInvoiceRequest(authentication.getName(), orderId, request)
        );
    }

    @PostMapping("/order/{orderId}/issue")
    public ResponseEntity<InvoiceSummaryResponse> issueInvoiceForOrder(
            @PathVariable String orderId,
            @Valid @RequestBody InvoiceRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(
                invoiceUseCase.issueInvoiceForOrder(authentication.getName(), orderId, request)
        );
    }

    @PostMapping("/order/{orderId}/resend-email")
    public ResponseEntity<InvoiceSummaryResponse> resendInvoiceEmail(
            @PathVariable String orderId,
            @Valid @RequestBody(required = false) ResendInvoiceEmailRequest request,
            Authentication authentication) {
        String email = request != null ? request.email() : null;
        return ResponseEntity.ok(
                invoiceUseCase.resendInvoiceEmail(authentication.getName(), orderId, email)
        );
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<InvoiceSummaryResponse> getInvoiceForOrder(
            @PathVariable String orderId,
            Authentication authentication) {
        return invoiceUseCase.getInvoiceForOrder(authentication.getName(), orderId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping(value = "/order/{orderId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadInvoicePdf(
            @PathVariable String orderId,
            Authentication authentication) {
        byte[] pdfBytes = invoiceUseCase.downloadInvoicePdf(authentication.getName(), orderId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(
                ContentDisposition.attachment().filename("factura-" + orderId + ".pdf").build()
        );
        return ResponseEntity.ok().headers(headers).body(pdfBytes);
    }

    @GetMapping(value = "/order/{orderId}/xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<byte[]> downloadInvoiceXml(
            @PathVariable String orderId,
            Authentication authentication) {
        byte[] xmlBytes = invoiceUseCase.downloadInvoiceXml(authentication.getName(), orderId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        headers.setContentDisposition(
                ContentDisposition.attachment().filename("factura-" + orderId + ".xml").build()
        );
        return ResponseEntity.ok().headers(headers).body(xmlBytes);
    }
}
