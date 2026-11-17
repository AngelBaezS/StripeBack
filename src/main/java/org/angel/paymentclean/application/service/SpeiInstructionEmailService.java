package org.angel.paymentclean.application.service;

import org.angel.paymentclean.domain.model.SpeiPaymentDetails;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Envía por correo electrónico las instrucciones de transferencia SPEI junto con la ficha PDF adjunta
 * utilizando Mailtrap Sandbox API (en lugar de SMTP).
 */
@Service
public class SpeiInstructionEmailService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final MailtrapEmailService mailtrapEmailService;

    @Autowired
    public SpeiInstructionEmailService(MailtrapEmailService mailtrapEmailService) {
        this.mailtrapEmailService = mailtrapEmailService;
    }

    public boolean sendSpeiInstructions(
            String recipientEmail,
            String customerName,
            String orderId,
            SpeiPaymentDetails speiDetails,
            LocalDateTime expiresAt,
            byte[] pdfBytes
    ) {
        if (recipientEmail == null || recipientEmail.isBlank() || speiDetails == null || mailtrapEmailService == null) {
            return false;
        }

        List<MailtrapEmailService.AttachmentData> attachments = (pdfBytes != null && pdfBytes.length > 0)
                ? List.of(new MailtrapEmailService.AttachmentData(
                        "ficha-spei-" + orderId + ".pdf",
                        pdfBytes,
                        "application/pdf"
                ))
                : List.of();

        return mailtrapEmailService.sendEmail(
                recipientEmail.trim(),
                "Instrucciones de Pago SPEI - Orden " + orderId,
                buildHtmlBody(customerName, orderId, speiDetails, expiresAt),
                "Instrucciones SPEI para orden " + orderId + " (CLABE: " + speiDetails.clabe() + ")",
                attachments,
                "spei-instructions"
        );
    }

    private String buildHtmlBody(
            String customerName,
            String orderId,
            SpeiPaymentDetails speiDetails,
            LocalDateTime expiresAt
    ) {
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        long cents = speiDetails.amountCents() != null ? speiDetails.amountCents() : 0L;
        String amountStr = "$" + nf.format(cents / 100.0) + " MXN";
        String expiresStr = expiresAt != null ? expiresAt.format(DATE_FORMATTER) : "48 horas";
        String greetingName = (customerName != null && !customerName.isBlank()) ? customerName : "Cliente";
        String bankStr = (speiDetails.bankName() != null ? speiDetails.bankName() : "STP")
                + (speiDetails.bankCode() != null ? " (" + speiDetails.bankCode() + ")" : "");
        String hostedLinkHtml = (speiDetails.hostedInstructionsUrl() != null && !speiDetails.hostedInstructionsUrl().isBlank())
                ? "<p style=\"margin-top:16px;\"><a href=\"" + escapeHtml(speiDetails.hostedInstructionsUrl())
                  + "\" style=\"background:#4f46e5;color:#fff;padding:10px 18px;border-radius:8px;text-decoration:none;font-weight:bold;\">Ver ficha en línea de Stripe</a></p>"
                : "";

        return """
                <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto;color:#0f172a;border:1px solid #e2e8f0;border-radius:12px;overflow:hidden;">
                  <div style="background:#0f172a;color:#ffffff;padding:20px 24px;">
                    <h2 style="margin:0;font-size:18px;">Instrucciones de Pago por Transferencia SPEI</h2>
                    <p style="margin:6px 0 0;font-size:13px;color:#cbd5e1;">Orden: %s</p>
                  </div>
                  <div style="padding:24px;">
                    <p>Hola <strong>%s</strong>,</p>
                    <p>Adjuntamos tu <strong>Ficha de Pago SPEI en PDF</strong> con todos los datos necesarios para completar tu compra:</p>
                    <div style="background:#f8fafc;border:1px solid #cbd5e1;border-radius:8px;padding:16px;margin:16px 0;">
                      <p style="margin:4px 0;"><strong>Monto exacto:</strong> %s</p>
                      <p style="margin:4px 0;"><strong>Banco receptor:</strong> %s</p>
                      <p style="margin:4px 0;"><strong>CLABE (18 dígitos):</strong> <code style="font-size:15px;font-weight:bold;">%s</code></p>
                      <p style="margin:4px 0;"><strong>Referencia:</strong> <code>%s</code></p>
                      <p style="margin:4px 0;color:#b45309;"><strong>Fecha límite de pago:</strong> %s</p>
                    </div>
                    %s
                    <p style="font-size:12px;color:#64748b;margin-top:20px;">
                      Si el pago no se acredita dentro de las próximas 48 horas, la orden se cancelará automáticamente.
                    </p>
                  </div>
                </div>
                """.formatted(
                escapeHtml(orderId),
                escapeHtml(greetingName),
                escapeHtml(amountStr),
                escapeHtml(bankStr),
                escapeHtml(speiDetails.clabe() != null ? speiDetails.clabe() : ""),
                escapeHtml(speiDetails.reference() != null ? speiDetails.reference() : orderId),
                escapeHtml(expiresStr),
                hostedLinkHtml
        );
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
