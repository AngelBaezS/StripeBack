package org.angel.paymentclean.application.service;

import io.mailtrap.client.MailtrapClient;
import io.mailtrap.config.MailtrapConfig;
import io.mailtrap.exception.InvalidRequestBodyException;
import io.mailtrap.exception.http.HttpException;
import io.mailtrap.factory.MailtrapClientFactory;
import io.mailtrap.model.request.emails.Address;
import io.mailtrap.model.request.emails.EmailAttachment;
import io.mailtrap.model.request.emails.MailtrapMail;
import io.mailtrap.model.response.accounts.AccountsResponse;
import io.mailtrap.model.response.emails.SendResponse;
import io.mailtrap.model.response.inboxes.InboxResponse;
import org.angel.paymentclean.domain.model.Order;
import org.angel.paymentclean.domain.model.OrderInvoice;
import org.angel.paymentclean.domain.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Servicio centralizado de envío de correos mediante Mailtrap Java SDK 1.3.0.
 * Por defecto opera contra Mailtrap Email Sending API (https://send.api.mailtrap.io).
 */
@Service
public class MailtrapEmailService {

    private static final Logger log = LoggerFactory.getLogger(MailtrapEmailService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final String MAILTRAP_LOGS_URL = "https://mailtrap.io/sending/email_logs";

    public record AttachmentData(String filename, byte[] bytes, String contentType) {}

    public record OrderItemSummary(String name, long quantity, long subtotalCents) {}

    private final String apiToken;
    private final boolean sandbox;
    private final Long configuredInboxId;
    private final String fromEmail;
    private final String fromName;
    private final String overrideTo;
    private final Function<MailtrapConfig, MailtrapClient> clientFactory;
    private final AtomicReference<Long> resolvedSandboxInboxId = new AtomicReference<>();
    private final AtomicReference<MailtrapClient> cachedClient = new AtomicReference<>();

    @Autowired
    public MailtrapEmailService(
            @Value("${mailtrap.api.token:}") String apiToken,
            @Value("${mailtrap.sandbox:false}") boolean sandbox,
            @Value("${mailtrap.inbox-id:}") String configuredInboxId,
            @Value("${mailtrap.from.email:hello@demomailtrap.com}") String fromEmail,
            @Value("${mailtrap.from.name:Pasarela de Pagos}") String fromName,
            @Value("${mailtrap.override-to:}") String overrideTo
    ) {
        this(
                apiToken,
                sandbox,
                parseInboxId(configuredInboxId),
                fromEmail,
                fromName,
                overrideTo,
                MailtrapClientFactory::createMailtrapClient
        );
    }

    public MailtrapEmailService(
            String apiToken,
            boolean sandbox,
            Long configuredInboxId,
            String fromEmail,
            String fromName,
            Function<MailtrapConfig, MailtrapClient> clientFactory
    ) {
        this(apiToken, sandbox, configuredInboxId, fromEmail, fromName, "", clientFactory);
    }

    public MailtrapEmailService(
            String apiToken,
            boolean sandbox,
            Long configuredInboxId,
            String fromEmail,
            String fromName,
            String overrideTo,
            Function<MailtrapConfig, MailtrapClient> clientFactory
    ) {
        this.apiToken = apiToken != null ? apiToken.trim() : "";
        this.sandbox = sandbox;
        this.configuredInboxId = configuredInboxId;
        this.fromEmail = (fromEmail != null && !fromEmail.isBlank()) ? fromEmail.trim() : "hello@demomailtrap.com";
        this.fromName = (fromName != null && !fromName.isBlank()) ? fromName.trim() : "Pasarela de Pagos";
        this.overrideTo = overrideTo != null ? overrideTo.trim() : "";
        this.clientFactory = clientFactory;
        if (configuredInboxId != null && configuredInboxId > 0) {
            this.resolvedSandboxInboxId.set(configuredInboxId);
        }
    }

    public boolean sendEmail(
            String recipientEmail,
            String subject,
            String htmlBody,
            String textBody,
            List<AttachmentData> attachments,
            String category
    ) {
        String effectiveRecipient = resolveRecipient(recipientEmail);
        if (effectiveRecipient == null) {
            return false;
        }
        if (apiToken.isBlank()) {
            log.info("📧 [Mailtrap] MAILTRAP_TOKEN no configurado; se omite envío de '{}' a {}.",
                    subject, effectiveRecipient);
            return false;
        }

        if (!overrideTo.isBlank() && recipientEmail != null && !overrideTo.equalsIgnoreCase(recipientEmail.trim())) {
            log.info("📬 [Mailtrap] Redirigiendo destinatario de prueba '{}' -> '{}' (MAILTRAP_OVERRIDE_TO).",
                    recipientEmail.trim(), effectiveRecipient);
        }

        try {
            MailtrapClient client = resolveClient();
            if (client == null) {
                return false;
            }

            List<EmailAttachment> sdkAttachments = new ArrayList<>();
            if (attachments != null) {
                for (AttachmentData item : attachments) {
                    if (item != null && item.bytes() != null && item.bytes().length > 0) {
                        sdkAttachments.add(
                                EmailAttachment.builder()
                                        .filename(item.filename())
                                        .type(item.contentType())
                                        .content(Base64.getEncoder().encodeToString(item.bytes()))
                                        .disposition("attachment")
                                        .build()
                        );
                    }
                }
            }

            MailtrapMail.MailtrapMailBuilder mailBuilder = MailtrapMail.builder()
                    .from(new Address(fromEmail, fromName))
                    .to(List.of(new Address(effectiveRecipient)))
                    .subject(subject)
                    .html(htmlBody)
                    .text(textBody != null && !textBody.isBlank() ? textBody : subject);

            if (!sdkAttachments.isEmpty()) {
                mailBuilder.attachments(sdkAttachments);
            }
            if (category != null && !category.isBlank() && !sandbox) {
                mailBuilder.category(category);
            }

            SendResponse response = client.send(mailBuilder.build());
            boolean success = response != null && response.isSuccess();
            if (success) {
                log.info("📧 [Mailtrap {}] Correo '{}' enviado a {} (messageIds={}). Logs: {}",
                        sandbox ? "Sandbox" : "Sending",
                        subject,
                        effectiveRecipient,
                        response.getMessageIds(),
                        MAILTRAP_LOGS_URL);
            } else {
                log.warn("⚠️ [Mailtrap {}] Mailtrap no confirmó éxito al enviar '{}' a {}: {}",
                        sandbox ? "Sandbox" : "Sending",
                        subject,
                        effectiveRecipient,
                        response);
            }
            return success;
        } catch (HttpException httpEx) {
            log.warn("⚠️ [Mailtrap {}] Error HTTP {} al enviar '{}' a {} (from='{}'): {}",
                    sandbox ? "Sandbox" : "Sending",
                    httpEx.getStatusCode(),
                    subject,
                    effectiveRecipient,
                    fromEmail,
                    httpEx.getMessage());
            return false;
        } catch (InvalidRequestBodyException valEx) {
            log.warn("⚠️ [Mailtrap {}] Payload inválido al enviar '{}' a {}: {}",
                    sandbox ? "Sandbox" : "Sending",
                    subject,
                    effectiveRecipient,
                    valEx.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("⚠️ [Mailtrap {}] Error inesperado enviando '{}' a {}: {}",
                    sandbox ? "Sandbox" : "Sending",
                    subject,
                    effectiveRecipient,
                    e.getMessage());
            return false;
        }
    }

    public boolean sendPaymentConfirmationEmail(
            Order order,
            User user,
            List<OrderItemSummary> items,
            boolean willIssueInvoice
    ) {
        if (order == null || user == null) {
            return false;
        }
        String candidateEmail = user.email();
        if ((candidateEmail == null || candidateEmail.isBlank()) && overrideTo.isBlank()) {
            return false;
        }

        String amountStr = formatAmount(order.totalAmountCents(), order.currency());
        String greetingName = (user.username() != null && !user.username().isBlank()) ? user.username() : "Cliente";

        StringBuilder itemsRows = new StringBuilder();
        if (items != null && !items.isEmpty()) {
            for (OrderItemSummary item : items) {
                itemsRows.append("<tr>")
                        .append("<td style=\"padding:8px 0;border-bottom:1px solid #e2e8f0;\">")
                        .append(escapeHtml(item.name()))
                        .append(" <span style=\"color:#64748b;\">× ").append(item.quantity()).append("</span></td>")
                        .append("<td style=\"padding:8px 0;border-bottom:1px solid #e2e8f0;text-align:right;font-weight:bold;\">")
                        .append(escapeHtml(formatAmount(item.subtotalCents(), order.currency())))
                        .append("</td>")
                        .append("</tr>");
            }
        } else {
            itemsRows.append("<tr><td style=\"padding:8px 0;\">Compra en tienda</td><td style=\"padding:8px 0;text-align:right;font-weight:bold;\">")
                    .append(escapeHtml(amountStr))
                    .append("</td></tr>");
        }

        String invoiceBanner = willIssueInvoice
                ? "<p style=\"margin-top:16px;padding:12px;background:#eff6ff;border:1px solid #bfdbfe;border-radius:8px;color:#1e40af;font-size:13px;\">"
                  + "Tu solicitud de factura CFDI 4.0 se está procesando automáticamente y recibirás los archivos PDF y XML en un correo separado.</p>"
                : "<p style=\"margin-top:16px;font-size:12px;color:#64748b;\">"
                  + "Si requieres factura CFDI 4.0, puedes solicitarla en cualquier momento desde la sección <strong>Mis Pedidos</strong>.</p>";

        String html = """
                <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto;color:#0f172a;border:1px solid #e2e8f0;border-radius:12px;overflow:hidden;">
                  <div style="background:#0f172a;color:#ffffff;padding:20px 24px;">
                    <h2 style="margin:0;font-size:18px;">¡Pago Confirmado!</h2>
                    <p style="margin:6px 0 0;font-size:13px;color:#cbd5e1;">Orden: %s</p>
                  </div>
                  <div style="padding:24px;">
                    <p>Hola <strong>%s</strong>,</p>
                    <p>Confirmamos que tu pago mediante Stripe por <strong>%s</strong> fue acreditado exitosamente.</p>
                    <table style="width:100%%;border-collapse:collapse;margin:16px 0;font-size:14px;">
                      %s
                    </table>
                    <p style="font-size:15px;font-weight:bold;text-align:right;">Total pagado: %s</p>
                    %s
                  </div>
                </div>
                """.formatted(
                escapeHtml(order.id()),
                escapeHtml(greetingName),
                escapeHtml(amountStr),
                itemsRows,
                escapeHtml(amountStr),
                invoiceBanner
        );

        return sendEmail(
                candidateEmail,
                "Confirmación de compra - Orden " + order.id(),
                html,
                "Confirmación de compra para la orden " + order.id() + " por " + amountStr,
                List.of(),
                "payment-confirmation"
        );
    }

    public boolean sendInvoiceEmail(
            OrderInvoice invoice,
            Order order,
            byte[] pdfBytes,
            byte[] xmlBytes
    ) {
        if (invoice == null) {
            return false;
        }
        String candidateEmail = invoice.email();
        if ((candidateEmail == null || candidateEmail.isBlank()) && overrideTo.isBlank()) {
            return false;
        }

        String amountStr = order != null ? formatAmount(order.totalAmountCents(), order.currency()) : "";
        String issuedDateStr = invoice.issuedAt() != null ? invoice.issuedAt().format(DATE_FORMATTER) : "";

        List<AttachmentData> attachments = new ArrayList<>();
        if (pdfBytes != null && pdfBytes.length > 0) {
            attachments.add(new AttachmentData("factura-" + invoice.orderId() + ".pdf", pdfBytes, "application/pdf"));
        }
        if (xmlBytes != null && xmlBytes.length > 0) {
            attachments.add(new AttachmentData("factura-" + invoice.orderId() + ".xml", xmlBytes, "application/xml"));
        }

        StringBuilder downloadLinks = new StringBuilder();
        if (invoice.pdfUrl() != null && !invoice.pdfUrl().isBlank()) {
            downloadLinks.append("<a href=\"").append(escapeHtml(invoice.pdfUrl()))
                    .append("\" style=\"display:inline-block;margin-right:10px;background:#0f172a;color:#fff;padding:10px 16px;border-radius:8px;text-decoration:none;font-size:13px;font-weight:bold;\">Descargar PDF</a>");
        }
        if (invoice.xmlUrl() != null && !invoice.xmlUrl().isBlank()) {
            downloadLinks.append("<a href=\"").append(escapeHtml(invoice.xmlUrl()))
                    .append("\" style=\"display:inline-block;background:#334155;color:#fff;padding:10px 16px;border-radius:8px;text-decoration:none;font-size:13px;font-weight:bold;\">Descargar XML</a>");
        }

        String html = """
                <div style="font-family:Arial,sans-serif;max-width:600px;margin:0 auto;color:#0f172a;border:1px solid #e2e8f0;border-radius:12px;overflow:hidden;">
                  <div style="background:#0f172a;color:#ffffff;padding:20px 24px;">
                    <h2 style="margin:0;font-size:18px;">Factura Electrónica CFDI 4.0</h2>
                    <p style="margin:6px 0 0;font-size:13px;color:#cbd5e1;">Orden: %s</p>
                  </div>
                  <div style="padding:24px;">
                    <p>Hola <strong>%s</strong>,</p>
                    <p>Tu comprobante fiscal digital (CFDI 4.0) ha sido timbrado exitosamente. Adjuntamos el PDF y XML correspondientes:</p>
                    <div style="background:#f8fafc;border:1px solid #cbd5e1;border-radius:8px;padding:16px;margin:16px 0;font-size:13px;">
                      <p style="margin:4px 0;"><strong>Folio Fiscal (UUID):</strong> <code>%s</code></p>
                      <p style="margin:4px 0;"><strong>RFC Receptor:</strong> <code>%s</code></p>
                      <p style="margin:4px 0;"><strong>Uso de CFDI:</strong> %s · <strong>Régimen:</strong> %s</p>
                      <p style="margin:4px 0;"><strong>Total Facturado:</strong> %s</p>
                      <p style="margin:4px 0;"><strong>Fecha de Timbrado:</strong> %s</p>
                    </div>
                    <div style="margin-top:16px;">%s</div>
                  </div>
                </div>
                """.formatted(
                escapeHtml(invoice.orderId()),
                escapeHtml(invoice.legalName()),
                escapeHtml(invoice.uuid() != null ? invoice.uuid() : invoice.cfdiExpressId()),
                escapeHtml(invoice.rfc()),
                escapeHtml(invoice.cfdiUse()),
                escapeHtml(invoice.taxRegime()),
                escapeHtml(amountStr),
                escapeHtml(issuedDateStr),
                downloadLinks
        );

        return sendEmail(
                candidateEmail,
                "Factura CFDI 4.0 - Orden " + invoice.orderId(),
                html,
                "Factura CFDI 4.0 (UUID: " + invoice.uuid() + ") para la orden " + invoice.orderId(),
                attachments,
                "cfdi-invoice"
        );
    }

    private String resolveRecipient(String recipientEmail) {
        if (overrideTo != null && !overrideTo.isBlank()) {
            return overrideTo;
        }
        return (recipientEmail != null && !recipientEmail.isBlank()) ? recipientEmail.trim() : null;
    }

    private MailtrapClient resolveClient() {
        MailtrapClient existing = cachedClient.get();
        if (existing != null) {
            return existing;
        }

        if (!sandbox) {
            MailtrapConfig config = new MailtrapConfig.Builder()
                    .token(apiToken)
                    .build();
            MailtrapClient client = clientFactory.apply(config);
            cachedClient.compareAndSet(null, client);
            return client;
        }

        Long inboxId = resolveSandboxInboxId();
        if (inboxId == null || inboxId <= 0) {
            log.warn("⚠️ [Mailtrap Sandbox] No se encontró MAILTRAP_INBOX_ID ni se pudo autodescubrir un inbox de pruebas.");
            return null;
        }

        MailtrapConfig sandboxConfig = new MailtrapConfig.Builder()
                .token(apiToken)
                .sandbox(true)
                .inboxId(inboxId)
                .build();
        MailtrapClient sandboxClient = clientFactory.apply(sandboxConfig);
        cachedClient.compareAndSet(null, sandboxClient);
        return sandboxClient;
    }

    private Long resolveSandboxInboxId() {
        Long cached = resolvedSandboxInboxId.get();
        if (cached != null && cached > 0) {
            return cached;
        }
        if (configuredInboxId != null && configuredInboxId > 0) {
            resolvedSandboxInboxId.compareAndSet(null, configuredInboxId);
            return configuredInboxId;
        }

        try {
            MailtrapClient discoveryClient = clientFactory.apply(new MailtrapConfig.Builder().token(apiToken).build());
            List<AccountsResponse> accounts = discoveryClient.generalApi().accounts().getAllAccounts();
            if (accounts != null) {
                for (AccountsResponse account : accounts) {
                    List<InboxResponse> inboxes = discoveryClient.testingApi().inboxes().getInboxes(account.getId());
                    if (inboxes != null && !inboxes.isEmpty()) {
                        Long id = inboxes.get(0).getId();
                        resolvedSandboxInboxId.compareAndSet(null, id);
                        log.info("📬 [Mailtrap Sandbox] Inbox autodescubierto: {} ({})",
                                id, inboxes.get(0).getName());
                        return id;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ [Mailtrap Sandbox] No fue posible autodescubrir el Inbox ID con MAILTRAP_TOKEN: {}", e.getMessage());
        }
        return null;
    }

    private static Long parseInboxId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String formatAmount(Long cents, String currency) {
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        long value = cents != null ? cents : 0L;
        String curr = (currency != null && !currency.isBlank()) ? currency.trim().toUpperCase() : "MXN";
        return "$" + nf.format(value / 100.0) + " " + curr;
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
