package org.angel.paymentclean.domain.port.out;

import java.math.BigDecimal;
import java.util.List;

public interface CfdiExpressPort {

    record CfdiInvoiceLineItem(
            String productId,
            String productCode,
            String unitCode,
            String description,
            long quantity,
            BigDecimal unitPricePesos,
            double ivaRate
    ) {
        public CfdiInvoiceLineItem(
                String productCode,
                String unitCode,
                String description,
                long quantity,
                BigDecimal unitPricePesos,
                double ivaRate
        ) {
            this(null, productCode, unitCode, description, quantity, unitPricePesos, ivaRate);
        }
    }

    record CfdiStampCommand(
            String orderId,
            String rfc,
            String legalName,
            String zipCode,
            String taxRegime,
            String cfdiUse,
            String formaPago,
            String metodoPago,
            List<CfdiInvoiceLineItem> items
    ) {}

    record CfdiStampResult(
            String cfdiExpressId,
            String uuid,
            String status,
            String pdfUrl,
            String xmlUrl,
            byte[] pdfBytes,
            byte[] xmlBytes
    ) {}

    CfdiStampResult stampInvoice(CfdiStampCommand command);

    CfdiStampResult refreshInvoiceFiles(String cfdiExpressId);

    List<CfdiStampResult> listInvoicesByQuery(String query, String merchantId);

    CfdiStampResult cancelInvoice(String cfdiExpressId, String motivo, String folioSustitucion);

    byte[] downloadFileBytes(String fileUrl);
}
