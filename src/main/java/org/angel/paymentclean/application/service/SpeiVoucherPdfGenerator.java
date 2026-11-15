package org.angel.paymentclean.application.service;

import org.angel.paymentclean.domain.model.Order;
import org.angel.paymentclean.domain.model.SpeiPaymentDetails;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Generador nativo de Ficha de Pago SPEI en formato PDF (%PDF-1.4) sin
 * dependencias externas.
 */
@Service
public class SpeiVoucherPdfGenerator {

    private static final float PAGE_WIDTH = 595f;
    private static final float PAGE_HEIGHT = 842f;
    private static final float MARGIN_X = 40f;
    private static final float CONTENT_WIDTH = PAGE_WIDTH - (MARGIN_X * 2);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public record VoucherItem(String name, long quantity, long unitAmountCents, long subtotalCents) {
    }

    public byte[] generateVoucherPdf(
            Order order,
            SpeiPaymentDetails speiDetails,
            String customerName,
            String recipientEmail,
            LocalDateTime expiresAt,
            List<VoucherItem> items) {
        if (order == null || speiDetails == null) {
            throw new IllegalArgumentException("La orden y los datos SPEI son obligatorios para generar el PDF.");
        }

        List<String> pageStreams = buildPageStreams(order, speiDetails, customerName, recipientEmail, expiresAt, items);
        return assemblePdfDocument(pageStreams);
    }

    private List<String> buildPageStreams(
            Order order,
            SpeiPaymentDetails speiDetails,
            String customerName,
            String recipientEmail,
            LocalDateTime expiresAt,
            List<VoucherItem> items) {
        List<String> streams = new ArrayList<>();
        StringBuilder sb = new StringBuilder();

        long amountCents = speiDetails.amountCents() != null
                ? speiDetails.amountCents()
                : (order.totalAmountCents() != null ? order.totalAmountCents() : 0L);
        String formattedAmount = formatMxn(amountCents);
        String bankDisplay = formatBank(speiDetails.bankName(), speiDetails.bankCode());
        String clabe = speiDetails.clabe() != null ? speiDetails.clabe() : "No disponible";
        String reference = speiDetails.reference() != null ? speiDetails.reference() : order.id();
        String createdStr = order.createdAt() != null ? order.createdAt().format(DATE_FORMATTER)
                : LocalDateTime.now().format(DATE_FORMATTER);
        String expiresStr = expiresAt != null ? expiresAt.format(DATE_FORMATTER) : "48 horas desde la creacion";
        String safeCustomer = (customerName != null && !customerName.isBlank()) ? customerName : "Cliente";
        String safeEmail = (recipientEmail != null && !recipientEmail.isBlank()) ? recipientEmail : "No especificado";

        // 1. Header Banner
        fillRect(sb, 0, PAGE_HEIGHT - 86, PAGE_WIDTH, 86, 0.06f, 0.09f, 0.16f);
        drawText(sb, "F2", 18, MARGIN_X, PAGE_HEIGHT - 38, 1f, 1f, 1f, "PASARELA DE PAGOS TEST");
        drawText(sb, "F1", 10.5f, MARGIN_X, PAGE_HEIGHT - 58, 0.78f, 0.84f, 0.96f,
                "FICHA DE PAGO POR TRANSFERENCIA ELECTRONICA (SPEI)");
        drawText(sb, "F2", 10, PAGE_WIDTH - MARGIN_X - 155, PAGE_HEIGHT - 38, 0.50f, 0.82f, 0.99f,
                "ESTADO: PENDIENTE DE PAGO");

        // 2. Order & Customer Metadata Card
        float y = PAGE_HEIGHT - 104;
        fillRect(sb, MARGIN_X, y - 66, CONTENT_WIDTH, 66, 0.97f, 0.98f, 0.99f);
        strokeRect(sb, MARGIN_X, y - 66, CONTENT_WIDTH, 66, 0.85f, 0.88f, 0.92f, 0.8f);

        drawText(sb, "F2", 9, MARGIN_X + 12, y - 18, 0.35f, 0.40f, 0.48f, "FOLIO DE ORDEN:");
        drawText(sb, "F3", 9.5f, MARGIN_X + 108, y - 18, 0.06f, 0.09f, 0.16f, order.id());

        drawText(sb, "F2", 9, MARGIN_X + 295, y - 18, 0.35f, 0.40f, 0.48f, "FECHA EMISION:");
        drawText(sb, "F1", 9.5f, MARGIN_X + 382, y - 18, 0.06f, 0.09f, 0.16f, createdStr);

        drawText(sb, "F2", 9, MARGIN_X + 12, y - 36, 0.35f, 0.40f, 0.48f, "CLIENTE:");
        drawText(sb, "F1", 9.5f, MARGIN_X + 108, y - 36, 0.06f, 0.09f, 0.16f, truncate(safeCustomer, 30));

        drawText(sb, "F2", 9, MARGIN_X + 295, y - 36, 0.35f, 0.40f, 0.48f, "LIMITE DE PAGO:");
        drawText(sb, "F2", 9.5f, MARGIN_X + 382, y - 36, 0.72f, 0.25f, 0.05f, expiresStr);

        drawText(sb, "F2", 9, MARGIN_X + 12, y - 54, 0.35f, 0.40f, 0.48f, "CORREO ENVIO:");
        drawText(sb, "F1", 9.5f, MARGIN_X + 108, y - 54, 0.06f, 0.09f, 0.16f, truncate(safeEmail, 58));

        // 3. Amount Highlight Box
        y -= 82;
        fillRect(sb, MARGIN_X, y - 54, CONTENT_WIDTH, 54, 0.93f, 0.95f, 1.0f);
        strokeRect(sb, MARGIN_X, y - 54, CONTENT_WIDTH, 54, 0.31f, 0.27f, 0.90f, 1.2f);
        drawText(sb, "F2", 9.5f, MARGIN_X + 14, y - 20, 0.26f, 0.22f, 0.65f,
                "MONTO EXACTO A TRANSFERIR (PESOS MEXICANOS)");
        drawText(sb, "F2", 20, MARGIN_X + 14, y - 43, 0.12f, 0.10f, 0.45f, formattedAmount);

        // 4. SPEI Banking Instructions Box
        y -= 70;
        fillRect(sb, MARGIN_X, y - 122, CONTENT_WIDTH, 122, 1f, 1f, 1f);
        strokeRect(sb, MARGIN_X, y - 122, CONTENT_WIDTH, 122, 0.78f, 0.82f, 0.88f, 1f);
        fillRect(sb, MARGIN_X, y - 24, CONTENT_WIDTH, 24, 0.94f, 0.96f, 0.98f);
        drawText(sb, "F2", 10, MARGIN_X + 12, y - 16, 0.06f, 0.09f, 0.16f, "DATOS BANCARIOS PARA TRANSFERENCIA SPEI");

        drawText(sb, "F2", 9, MARGIN_X + 14, y - 44, 0.35f, 0.40f, 0.48f, "BANCO RECEPTOR:");
        drawText(sb, "F2", 10.5f, MARGIN_X + 145, y - 44, 0.06f, 0.09f, 0.16f, truncate(bankDisplay, 48));

        drawText(sb, "F2", 9, MARGIN_X + 14, y - 68, 0.35f, 0.40f, 0.48f, "CLABE (18 DIGITOS):");
        fillRect(sb, MARGIN_X + 142, y - 75, 225, 19, 0.95f, 0.97f, 1.0f);
        drawText(sb, "F3", 12, MARGIN_X + 148, y - 69, 0.05f, 0.12f, 0.40f, clabe);

        drawText(sb, "F2", 9, MARGIN_X + 14, y - 92, 0.35f, 0.40f, 0.48f, "REFERENCIA / CONCEPTO:");
        drawText(sb, "F3", 10.5f, MARGIN_X + 145, y - 92, 0.06f, 0.09f, 0.16f, truncate(reference, 40));

        drawText(sb, "F2", 9, MARGIN_X + 14, y - 112, 0.35f, 0.40f, 0.48f, "BENEFICIARIO:");
        drawText(sb, "F1", 10, MARGIN_X + 145, y - 112, 0.06f, 0.09f, 0.16f, "Pasarela de Pagos Tech (Stripe MX)");

        // 5. Items Table
        y -= 138;
        drawText(sb, "F2", 10, MARGIN_X, y, 0.06f, 0.09f, 0.16f, "RESUMEN DE PRODUCTOS");
        y -= 10;
        y = drawItemsTableHeader(sb, y);

        List<VoucherItem> safeItems = (items != null) ? items : List.of();
        for (int i = 0; i < safeItems.size(); i++) {
            if (y < 185) {
                drawFooter(sb, streams.size() + 1);
                streams.add(sb.toString());
                sb = new StringBuilder();
                y = PAGE_HEIGHT - 60;
                drawText(sb, "F2", 11, MARGIN_X, y, 0.06f, 0.09f, 0.16f,
                        "FICHA SPEI - CONTINUACION (" + order.id() + ")");
                y -= 18;
                y = drawItemsTableHeader(sb, y);
            }

            VoucherItem item = safeItems.get(i);
            if (i % 2 == 1) {
                fillRect(sb, MARGIN_X, y - 18, CONTENT_WIDTH, 18, 0.98f, 0.98f, 0.99f);
            }
            drawText(sb, "F1", 9, MARGIN_X + 8, y - 13, 0.10f, 0.14f, 0.22f, truncate(item.name(), 44));
            drawText(sb, "F1", 9, MARGIN_X + 305, y - 13, 0.10f, 0.14f, 0.22f, String.valueOf(item.quantity()));
            drawText(sb, "F1", 9, MARGIN_X + 355, y - 13, 0.10f, 0.14f, 0.22f, formatMxnShort(item.unitAmountCents()));
            drawText(sb, "F2", 9, MARGIN_X + 435, y - 13, 0.06f, 0.09f, 0.16f, formatMxnShort(item.subtotalCents()));
            y -= 18;
        }

        if (safeItems.isEmpty()) {
            drawText(sb, "F1", 9, MARGIN_X + 8, y - 13, 0.35f, 0.40f, 0.48f, "Compra de orden " + order.id());
            drawText(sb, "F2", 9, MARGIN_X + 435, y - 13, 0.06f, 0.09f, 0.16f, formatMxnShort(amountCents));
            y -= 18;
        }

        drawLine(sb, MARGIN_X, y, PAGE_WIDTH - MARGIN_X, y, 0.78f, 0.82f, 0.88f, 0.8f);

        if (y < 165) {
            drawFooter(sb, streams.size() + 1);
            streams.add(sb.toString());
            sb = new StringBuilder();
            y = PAGE_HEIGHT - 60;
        }

        fillRect(sb, MARGIN_X, y - 22, CONTENT_WIDTH, 22, 0.93f, 0.95f, 0.99f);
        drawLine(sb, MARGIN_X, y - 22, PAGE_WIDTH - MARGIN_X, y - 22, 0.78f, 0.82f, 0.88f, 0.8f);
        drawText(sb, "F2", 9.5f, MARGIN_X + 8, y - 15, 0.06f, 0.09f, 0.16f, "Total");
        drawText(sb, "F2", 9.5f, MARGIN_X + 435, y - 15, 0.12f, 0.10f, 0.45f, formatMxn(amountCents));
        y -= 22;

        // 6. Step-by-Step Instructions Box
        if (y < 165) {
            drawFooter(sb, streams.size() + 1);
            streams.add(sb.toString());
            sb = new StringBuilder();
            y = PAGE_HEIGHT - 60;
        }

        y -= 20;
        fillRect(sb, MARGIN_X, y - 108, CONTENT_WIDTH, 108, 0.99f, 0.98f, 0.94f);
        strokeRect(sb, MARGIN_X, y - 108, CONTENT_WIDTH, 108, 0.92f, 0.76f, 0.45f, 0.9f);
        drawText(sb, "F2", 9.5f, MARGIN_X + 12, y - 18, 0.55f, 0.26f, 0.04f, "INSTRUCCIONES PARA REALIZAR TU PAGO");
        drawText(sb, "F1", 8.8f, MARGIN_X + 12, y - 35, 0.20f, 0.24f, 0.30f,
                "1. Ingresa a la banca movil o portal web de tu banco y selecciona Transferencia SPEI.");
        drawText(sb, "F1", 8.8f, MARGIN_X + 12, y - 50, 0.20f, 0.24f, 0.30f,
                "2. Registra la CLABE de 18 digitos y selecciona " + truncate(bankDisplay, 32)
                        + " como banco destino.");
        drawText(sb, "F1", 8.8f, MARGIN_X + 12, y - 65, 0.20f, 0.24f, 0.30f,
                "3. Ingresa el monto exacto (" + formattedAmount + ") y coloca la referencia indicada.");
        drawText(sb, "F2", 8.8f, MARGIN_X + 12, y - 83, 0.65f, 0.16f, 0.10f,
                "IMPORTANTE: Si no se recibe el pago antes de " + expiresStr
                        + ", la orden se cancelara automaticamente.");
        drawText(sb, "F1", 8.3f, MARGIN_X + 12, y - 98, 0.35f, 0.40f, 0.48f,
                "Tu pedido se confirmara automaticamente en segundos una vez acreditada la transferencia.");

        drawFooter(sb, streams.size() + 1);
        streams.add(sb.toString());
        return streams;
    }

    private float drawItemsTableHeader(StringBuilder sb, float y) {
        fillRect(sb, MARGIN_X, y - 20, CONTENT_WIDTH, 20, 0.15f, 0.20f, 0.30f);
        drawText(sb, "F2", 8.5f, MARGIN_X + 8, y - 14, 1f, 1f, 1f, "PRODUCTO");
        drawText(sb, "F2", 8.5f, MARGIN_X + 298, y - 14, 1f, 1f, 1f, "CANT.");
        drawText(sb, "F2", 8.5f, MARGIN_X + 355, y - 14, 1f, 1f, 1f, "P. UNIT.");
        drawText(sb, "F2", 8.5f, MARGIN_X + 435, y - 14, 1f, 1f, 1f, "SUBTOTAL");
        return y - 20;
    }

    private void drawFooter(StringBuilder sb, int pageNumber) {
        drawLine(sb, MARGIN_X, 42, PAGE_WIDTH - MARGIN_X, 42, 0.85f, 0.88f, 0.92f, 0.6f);
        drawText(sb, "F1", 8, MARGIN_X, 28, 0.45f, 0.50f, 0.58f,
                "Documento generado por Pasarela de Pagos Tech - Conserva este comprobante hasta confirmar tu pedido.");
        drawText(sb, "F1", 8, PAGE_WIDTH - MARGIN_X - 45, 28, 0.45f, 0.50f, 0.58f,
                "Pag. " + pageNumber);
    }

    private byte[] assemblePdfDocument(List<String> pageStreams) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            List<Integer> offsets = new ArrayList<>();
            // Index 0 placeholder for object 0
            offsets.add(0);

            writeAscii(out, "%PDF-1.4\n");

            int pageCount = pageStreams.size();
            // Object numbering:
            // 1: Catalog
            // 2: Pages
            // 3: Font F1 (Helvetica)
            // 4: Font F2 (Helvetica-Bold)
            // 5: Font F3 (Courier-Bold)
            // For each page i (0..pageCount-1):
            // pageObjId = 6 + i*2
            // contentObjId = 7 + i*2

            // Obj 1: Catalog
            offsets.add(out.size());
            writeAscii(out, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

            // Obj 2: Pages
            offsets.add(out.size());
            StringBuilder kids = new StringBuilder();
            for (int i = 0; i < pageCount; i++) {
                kids.append(6 + i * 2).append(" 0 R ");
            }
            writeAscii(out, "2 0 obj\n<< /Type /Pages /Kids [ " + kids + "] /Count " + pageCount + " >>\nendobj\n");

            // Obj 3: F1
            offsets.add(out.size());
            writeAscii(out,
                    "3 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>\nendobj\n");

            // Obj 4: F2
            offsets.add(out.size());
            writeAscii(out,
                    "4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>\nendobj\n");

            // Obj 5: F3
            offsets.add(out.size());
            writeAscii(out,
                    "5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Courier-Bold /Encoding /WinAnsiEncoding >>\nendobj\n");

            for (int i = 0; i < pageCount; i++) {
                int pageObjId = 6 + i * 2;
                int contentObjId = 7 + i * 2;
                byte[] streamBytes = pageStreams.get(i).getBytes(StandardCharsets.ISO_8859_1);

                offsets.add(out.size());
                writeAscii(out, pageObjId + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
                        + "/Resources << /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R >> >> "
                        + "/Contents " + contentObjId + " 0 R >>\nendobj\n");

                offsets.add(out.size());
                writeAscii(out, contentObjId + " 0 obj\n<< /Length " + streamBytes.length + " >>\nstream\n");
                out.write(streamBytes);
                writeAscii(out, "\nendstream\nendobj\n");
            }

            int xrefOffset = out.size();
            int totalObjects = offsets.size();
            writeAscii(out, "xref\n0 " + totalObjects + "\n");
            writeAscii(out, "0000000000 65535 f \n");
            for (int i = 1; i < totalObjects; i++) {
                writeAscii(out, String.format(Locale.US, "%010d 00000 n \n", offsets.get(i)));
            }
            writeAscii(out,
                    "trailer\n<< /Size " + totalObjects + " /Root 1 0 R >>\nstartxref\n" + xrefOffset + "\n%%EOF\n");
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo generar el PDF de la ficha SPEI.", e);
        }
    }

    private void writeAscii(ByteArrayOutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private void fillRect(StringBuilder sb, float x, float y, float w, float h, float r, float g, float b) {
        sb.append(String.format(Locale.US, "%.3f %.3f %.3f rg %.2f %.2f %.2f %.2f re f\n", r, g, b, x, y, w, h));
    }

    private void strokeRect(StringBuilder sb, float x, float y, float w, float h, float r, float g, float b,
            float lineWidth) {
        sb.append(String.format(Locale.US, "%.3f %.3f %.3f RG %.2f w %.2f %.2f %.2f %.2f re S\n", r, g, b, lineWidth, x,
                y, w, h));
    }

    private void drawLine(StringBuilder sb, float x1, float y1, float x2, float y2, float r, float g, float b,
            float lineWidth) {
        sb.append(String.format(Locale.US, "%.3f %.3f %.3f RG %.2f w %.2f %.2f m %.2f %.2f l S\n", r, g, b, lineWidth,
                x1, y1, x2, y2));
    }

    private void drawText(StringBuilder sb, String fontKey, float size, float x, float y, float r, float g, float b,
            String text) {
        sb.append(String.format(Locale.US, "BT /%s %.2f Tf %.3f %.3f %.3f rg %.2f %.2f Td (%s) Tj ET\n",
                fontKey, size, r, g, b, x, y, escapePdfString(text)));
    }

    private String escapePdfString(String input) {
        if (input == null) {
            return "";
        }
        String normalized = input
                .replace('\u2014', '-')
                .replace('\u2013', '-')
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .replace('\u201C', '"')
                .replace('\u201D', '"')
                .replace('\u2022', '*');

        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch == '\\' || ch == '(' || ch == ')') {
                out.append('\\').append(ch);
            } else if (ch >= 32 && ch <= 255) {
                out.append(ch);
            } else {
                out.append(' ');
            }
        }
        return out.toString();
    }

    private String formatMxn(long amountCents) {
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return "$" + nf.format(amountCents / 100.0) + " MXN";
    }

    private String formatMxnShort(long amountCents) {
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return "$" + nf.format(amountCents / 100.0);
    }

    private String formatBank(String bankName, String bankCode) {
        String base = (bankName != null && !bankName.isBlank()) ? bankName : "STP";
        if (bankCode != null && !bankCode.isBlank()) {
            return base + " (" + bankCode + ")";
        }
        return base;
    }

    private String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLen - 3)) + "...";
    }
}
