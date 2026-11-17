package org.angel.paymentclean.infrastructure.adapters.out.cfdi;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.angel.paymentclean.domain.exception.ArticleBusinessConflictException;
import org.angel.paymentclean.domain.exception.CfdiIntegrationException;
import org.angel.paymentclean.domain.port.out.CfdiExpressPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class CfdiExpressService implements CfdiExpressPort {

    private static final Logger log = LoggerFactory.getLogger(CfdiExpressService.class);
    private static final int FILE_POLL_ATTEMPTS = 6;
    private static final long FILE_POLL_DELAY_MS = 700L;
    private static final String GENERIC_RFC = "XAXX010101000";
    private static final ZoneId MEXICO_CITY_ZONE = ZoneId.of("America/Mexico_City");

    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final String baseUrl;
    private final String apiKey;
    private final String configuredMerchantId;
    private final String defaultProductCode;
    private final String defaultUnitCode;
    private final String emitterZipCode;
    private final AtomicReference<String> discoveredMerchantId = new AtomicReference<>();
    private final AtomicReference<String> discoveredMerchantZip = new AtomicReference<>();

    @Autowired
    public CfdiExpressService(
            @Value("${cfdi.express.base-url:https://api.cfdi.express/v1}") String baseUrl,
            @Value("${cfdi.express.api-key:}") String apiKey,
            @Value("${cfdi.express.merchant-id:}") String configuredMerchantId,
            @Value("${cfdi.express.default-product-code:01010101}") String defaultProductCode,
            @Value("${cfdi.express.default-unit-code:H87}") String defaultUnitCode,
            @Value("${app.emitter.cp:42501}") String emitterZipCode) {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
                baseUrl,
                apiKey,
                configuredMerchantId,
                defaultProductCode,
                defaultUnitCode,
                emitterZipCode);
    }

    public CfdiExpressService(
            String baseUrl,
            String apiKey,
            String configuredMerchantId,
            String defaultProductCode,
            String defaultUnitCode) {
        this(baseUrl, apiKey, configuredMerchantId, defaultProductCode, defaultUnitCode, "42501");
    }

    public CfdiExpressService(
            HttpClient httpClient,
            String baseUrl,
            String apiKey,
            String configuredMerchantId,
            String defaultProductCode,
            String defaultUnitCode) {
        this(httpClient, baseUrl, apiKey, configuredMerchantId, defaultProductCode, defaultUnitCode, "42501");
    }

    public CfdiExpressService(
            HttpClient httpClient,
            String baseUrl,
            String apiKey,
            String configuredMerchantId,
            String defaultProductCode,
            String defaultUnitCode,
            String emitterZipCode) {
        this.httpClient = httpClient;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.configuredMerchantId = configuredMerchantId != null ? configuredMerchantId.trim() : "";
        this.defaultProductCode = (defaultProductCode != null && !defaultProductCode.isBlank())
                ? defaultProductCode.trim()
                : "01010101";
        this.defaultUnitCode = (defaultUnitCode != null && !defaultUnitCode.isBlank())
                ? defaultUnitCode.trim()
                : "H87";
        this.emitterZipCode = (emitterZipCode != null && !emitterZipCode.isBlank())
                ? emitterZipCode.trim()
                : "42501";
    }

    @Override
    public CfdiStampResult stampInvoice(CfdiStampCommand command) {
        ensureApiKeyConfigured();
        String merchantId = resolveMerchantId();

        JsonObject payload = buildStampPayload(command, merchantId);
        String idempotencyKey = buildStampIdempotencyKey(command.orderId(), payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/invoices"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = sendString(request, "timbrar CFDI en CFDI Express");
        int statusCode = response.statusCode();
        if (statusCode != 200 && statusCode != 201 && statusCode != 202) {
            throw mapApiError("el timbrado", statusCode, response.body());
        }

        ParsedInvoiceResponse parsed = parseInvoiceResponse(response.body());
        ensureNotStampFailed(parsed);

        if (needsPolling(statusCode, parsed) && parsed.cfdiExpressId != null) {
            parsed = pollUntilStampedAndFilesReady(parsed);
        }

        String resolvedPdfUrl = resolveUrl(parsed.pdfUrl);
        String resolvedXmlUrl = resolveUrl(parsed.xmlUrl);
        byte[] pdfBytes = tryDownloadFile(resolvedPdfUrl);
        byte[] xmlBytes = tryDownloadFile(resolvedXmlUrl);

        log.info("🧾 [CFDI Express] Factura timbrada para orden {} -> id={}, uuid={}, status={}, filesStatus={}",
                command.orderId(), parsed.cfdiExpressId, parsed.uuid, parsed.status, parsed.filesStatus);

        return new CfdiStampResult(
                parsed.cfdiExpressId,
                parsed.uuid,
                parsed.status,
                resolvedPdfUrl,
                resolvedXmlUrl,
                pdfBytes,
                xmlBytes);
    }

    @Override
    public CfdiStampResult refreshInvoiceFiles(String cfdiExpressId) {
        ensureApiKeyConfigured();
        if (cfdiExpressId == null || cfdiExpressId.isBlank()) {
            throw new CfdiIntegrationException("No se encontró el ID de factura en CFDI Express.");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(
                        baseUrl + "/invoices/" + URLEncoder.encode(cfdiExpressId.trim(), StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = sendString(request, "consultar factura en CFDI Express");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw mapApiError("la consulta de la factura", response.statusCode(), response.body());
        }

        ParsedInvoiceResponse parsed = parseInvoiceResponse(response.body());
        String resolvedPdfUrl = resolveUrl(parsed.pdfUrl);
        String resolvedXmlUrl = resolveUrl(parsed.xmlUrl);
        return new CfdiStampResult(
                parsed.cfdiExpressId,
                parsed.uuid,
                parsed.status,
                resolvedPdfUrl,
                resolvedXmlUrl,
                tryDownloadFile(resolvedPdfUrl),
                tryDownloadFile(resolvedXmlUrl));
    }

    @Override
    public List<CfdiStampResult> listInvoicesByQuery(String query, String merchantId) {
        ensureApiKeyConfigured();
        StringBuilder uriBuilder = new StringBuilder(baseUrl).append("/invoices?limit=10");
        if (query != null && !query.isBlank()) {
            uriBuilder.append("&q=").append(URLEncoder.encode(query.trim(), StandardCharsets.UTF_8));
        }
        String effectiveMerchant = (merchantId != null && !merchantId.isBlank()) ? merchantId.trim()
                : configuredMerchantId;
        if (!effectiveMerchant.isBlank()) {
            uriBuilder.append("&merchantId=").append(URLEncoder.encode(effectiveMerchant, StandardCharsets.UTF_8));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uriBuilder.toString()))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = sendString(request, "listar facturas en CFDI Express");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw mapApiError("el listado de facturas", response.statusCode(), response.body());
        }

        JsonElement root = JsonParser.parseString(response.body());
        List<CfdiStampResult> results = new ArrayList<>();
        if (root.isJsonObject() && root.getAsJsonObject().has("data")
                && root.getAsJsonObject().get("data").isJsonArray()) {
            for (JsonElement item : root.getAsJsonObject().getAsJsonArray("data")) {
                if (item != null && item.isJsonObject()) {
                    ParsedInvoiceResponse parsed = parseInvoiceObject(item.getAsJsonObject());
                    results.add(new CfdiStampResult(
                            parsed.cfdiExpressId,
                            parsed.uuid,
                            parsed.status,
                            resolveUrl(parsed.pdfUrl),
                            resolveUrl(parsed.xmlUrl),
                            null,
                            null));
                }
            }
        }
        return results;
    }

    @Override
    public CfdiStampResult cancelInvoice(String cfdiExpressId, String motivo, String folioSustitucion) {
        ensureApiKeyConfigured();
        if (cfdiExpressId == null || cfdiExpressId.isBlank()) {
            throw new CfdiIntegrationException("Se requiere el ID de CFDI Express para cancelar la factura.");
        }
        String effectiveMotivo = nonBlankOrDefault(motivo, "02");

        JsonObject payload = new JsonObject();
        payload.addProperty("motivo", effectiveMotivo);
        if (folioSustitucion != null && !folioSustitucion.isBlank()) {
            payload.addProperty("folioSustitucion", folioSustitucion.trim());
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/invoices/" + URLEncoder.encode(cfdiExpressId.trim(), StandardCharsets.UTF_8)
                        + "/cancel"))
                .timeout(Duration.ofSeconds(25))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Idempotency-Key", "cfdi-cancel-" + cfdiExpressId.trim() + "-" + effectiveMotivo)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = sendString(request, "cancelar factura en CFDI Express");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw mapApiError("la cancelación ante el SAT", response.statusCode(), response.body());
        }

        ParsedInvoiceResponse parsed = parseInvoiceResponse(response.body());
        return new CfdiStampResult(
                nonBlankOrDefault(parsed.cfdiExpressId, cfdiExpressId),
                parsed.uuid,
                parsed.status,
                resolveUrl(parsed.pdfUrl),
                resolveUrl(parsed.xmlUrl),
                null,
                null);
    }

    @Override
    public byte[] downloadFileBytes(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) {
            throw new CfdiIntegrationException("La URL del archivo CFDI no está disponible.");
        }

        String resolved = resolveUrl(fileUrl);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(resolved))
                .timeout(Duration.ofSeconds(25))
                .GET();

        if (isSameApiHost(resolved) && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        try {
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body() == null) {
                throw new CfdiIntegrationException(
                        "Error al descargar comprobante CFDI (" + response.statusCode() + ").");
            }
            return response.body();
        } catch (CfdiIntegrationException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CfdiIntegrationException("La descarga del archivo CFDI fue interrumpida.", e);
        } catch (Exception e) {
            throw new CfdiIntegrationException("No se pudo descargar el archivo desde CFDI Express: " + e.getMessage(),
                    e);
        }
    }

    private String resolveMerchantId() {
        if (!configuredMerchantId.isBlank()) {
            return configuredMerchantId;
        }
        String cached = discoveredMerchantId.get();
        if (cached != null && !cached.isBlank()) {
            return cached;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/merchants"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = sendString(request, "consultar emisores en CFDI Express");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw mapApiError("la consulta de emisores", response.statusCode(), response.body());
        }

        String merchantId = extractFirstMerchantId(response.body());
        if (merchantId == null || merchantId.isBlank()) {
            throw new ArticleBusinessConflictException(
                    "Aún no hay un emisor (merchantId) registrado en CFDI Express. "
                            + "Configura tu emisor con su CSD en CFDI Express o agrega CFDI_EXPRESS_MERCHANT_ID en .env.");
        }

        discoveredMerchantId.compareAndSet(null, merchantId);
        return merchantId;
    }

    private String resolveMerchantZip(String merchantId) {
        String cachedZip = discoveredMerchantZip.get();
        if (cachedZip != null && !cachedZip.isBlank()) {
            return cachedZip;
        }
        if (merchantId != null && !merchantId.isBlank()) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/merchants/" + URLEncoder.encode(merchantId, StandardCharsets.UTF_8)))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    JsonElement parsed = JsonParser.parseString(response.body());
                    if (parsed.isJsonObject()) {
                        JsonObject root = parsed.getAsJsonObject();
                        JsonObject merchantObj = (root.has("data") && root.get("data").isJsonObject())
                                ? root.getAsJsonObject("data")
                                : root;
                        String zip = getString(merchantObj, "zip");
                        if (zip != null && !zip.isBlank()) {
                            discoveredMerchantZip.compareAndSet(null, zip.trim());
                            return zip.trim();
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // Respaldo con app.emitter.cp si el endpoint /merchants/{id} no responde
            }
        }
        return emitterZipCode;
    }

    private JsonObject buildStampPayload(CfdiStampCommand command, String merchantId) {
        String normalizedRfc = nonBlankOrDefault(command.rfc(), "").toUpperCase();
        String normalizedName = nonBlankOrDefault(command.legalName(), "").toUpperCase();
        boolean isGenericRfc = GENERIC_RFC.equals(normalizedRfc);

        String effectiveUsoCfdi = isGenericRfc
                ? "S01"
                : nonBlankOrDefault(command.cfdiUse(), "G03").toUpperCase();
        String effectiveZip = isGenericRfc
                ? nonBlankOrDefault(resolveMerchantZip(merchantId), command.zipCode())
                : nonBlankOrDefault(command.zipCode(), emitterZipCode);
        String effectiveRegimen = isGenericRfc
                ? "616"
                : nonBlankOrDefault(command.taxRegime(), "612");

        if (isGenericRfc && ("PÚBLICO EN GENERAL".equals(normalizedName) || "PUBLICO EN GENERAL".equals(normalizedName))) {
            normalizedName = "PUBLICO EN GENERAL";
        }

        JsonObject root = new JsonObject();
        root.addProperty("merchantId", merchantId);
        root.addProperty("formaPago", nonBlankOrDefault(command.formaPago(), "04"));
        root.addProperty("metodoPago", nonBlankOrDefault(command.metodoPago(), "PUE"));
        root.addProperty("usoCfdi", effectiveUsoCfdi);
        root.addProperty("currency", "MXN");
        if (command.orderId() != null && !command.orderId().isBlank()) {
            String safeFolio = command.orderId().replace("|", "").trim();
            if (safeFolio.length() > 40) {
                safeFolio = safeFolio.substring(0, 40);
            }
            if (!safeFolio.isEmpty()) {
                root.addProperty("folio", safeFolio);
            }
        }
        root.addProperty("pricesIncludeTax", true);
        root.addProperty("lineDecimals", 6);

        JsonObject receiver = new JsonObject();
        receiver.addProperty("rfc", normalizedRfc);
        receiver.addProperty("name", normalizedName);
        receiver.addProperty("zip", effectiveZip);
        receiver.addProperty("regimenFiscal", effectiveRegimen);
        root.add("receiver", receiver);

        if (isGenericRfc && "PUBLICO EN GENERAL".equals(normalizedName)) {
            ZonedDateTime nowMx = ZonedDateTime.now(MEXICO_CITY_ZONE);
            JsonObject infoGlobal = new JsonObject();
            infoGlobal.addProperty("periodicidad", "01");
            infoGlobal.addProperty("meses", String.format("%02d", nowMx.getMonthValue()));
            infoGlobal.addProperty("anio", nowMx.getYear());
            root.add("informacionGlobal", infoGlobal);
        }

        JsonArray itemsArray = new JsonArray();
        for (CfdiInvoiceLineItem item : command.items()) {
            JsonObject line = new JsonObject();
            line.addProperty("productCode", nonBlankOrDefault(item.productCode(), defaultProductCode));
            line.addProperty("unitCode", nonBlankOrDefault(item.unitCode(), defaultUnitCode));
            line.addProperty("description", nonBlankOrDefault(item.description(), "Compra en línea"));
            if (item.productId() != null && !item.productId().isBlank()) {
                String cleanId = item.productId().replace("|", "").trim();
                if (cleanId.length() > 100) {
                    cleanId = cleanId.substring(0, 100);
                }
                if (!cleanId.isEmpty()) {
                    line.addProperty("noIdentificacion", cleanId);
                }
            }
            line.addProperty("quantity", item.quantity() > 0 ? item.quantity() : 1L);
            BigDecimal unitPrice = item.unitPricePesos() != null
                    ? item.unitPricePesos().setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            line.addProperty("unitPrice", unitPrice);
            line.addProperty("ivaRate", item.ivaRate() > 0 ? item.ivaRate() : 0.16d);
            itemsArray.add(line);
        }
        root.add("items", itemsArray);
        return root;
    }

    private String buildStampIdempotencyKey(String orderId, JsonObject payload) {
        String signatureSource = gson.toJson(payload);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(signatureSource.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6 && i < hash.length; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return "cfdi-" + nonBlankOrDefault(orderId, "ord") + "-" + hex;
        } catch (Exception e) {
            return "cfdi-order-" + nonBlankOrDefault(orderId, "ord");
        }
    }

    private boolean needsPolling(int statusCode, ParsedInvoiceResponse parsed) {
        if (statusCode == 202 || "stamping".equalsIgnoreCase(parsed.status)) {
            return true;
        }
        if ("pending".equalsIgnoreCase(parsed.filesStatus) || "processing".equalsIgnoreCase(parsed.filesStatus)) {
            return true;
        }
        return parsed.pdfUrl == null || parsed.xmlUrl == null;
    }

    private ParsedInvoiceResponse pollUntilStampedAndFilesReady(ParsedInvoiceResponse initial) {
        ParsedInvoiceResponse current = initial;
        for (int i = 0; i < FILE_POLL_ATTEMPTS; i++) {
            try {
                Thread.sleep(FILE_POLL_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return current;
            }
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/invoices/"
                                + URLEncoder.encode(current.cfdiExpressId, StandardCharsets.UTF_8)))
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                HttpResponse<String> resp = httpClient.send(req,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    ParsedInvoiceResponse updated = parseInvoiceResponse(resp.body());
                    ensureNotStampFailed(updated);
                    current = updated;
                    boolean invoiceDone = !"stamping".equalsIgnoreCase(updated.status);
                    boolean filesDone = "ready".equalsIgnoreCase(updated.filesStatus)
                            || (updated.pdfUrl != null && updated.xmlUrl != null);
                    if (invoiceDone && filesDone) {
                        break;
                    }
                }
            } catch (CfdiIntegrationException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return current;
            } catch (Exception ex) {
                log.debug("Reintentando consultar factura CFDI {}: {}", current.cfdiExpressId, ex.getMessage());
            }
        }
        return current;
    }

    private void ensureNotStampFailed(ParsedInvoiceResponse parsed) {
        if ("stamp_failed".equalsIgnoreCase(parsed.status)) {
            throw new CfdiIntegrationException(
                    "CFDI Express no pudo timbrar el comprobante ante el SAT (status: stamp_failed).");
        }
        if ("failed".equalsIgnoreCase(parsed.filesStatus)) {
            log.warn("⚠️ [CFDI Express] Factura {} timbrada pero files.status='failed'.", parsed.cfdiExpressId);
        }
    }

    private CfdiIntegrationException mapApiError(String operation, int statusCode, String body) {
        String apiError = extractErrorMessage(body);
        String prefix = switch (statusCode) {
            case 402 -> "Créditos insuficientes o límite mensual alcanzado en CFDI Express (402)";
            case 409 -> "Ya existe una operación de timbrado en curso con esta clave en CFDI Express (409)";
            case 422 -> "Validación SAT rechazada o clave de idempotencia reutilizada en CFDI Express (422)";
            case 503 -> "Servicio del PAC/SAT temporalmente no disponible en CFDI Express (503)";
            default -> "CFDI Express rechazó " + operation + " (" + statusCode + ")";
        };
        return new CfdiIntegrationException(prefix + ": " + apiError);
    }

    private byte[] tryDownloadFile(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return downloadFileBytes(url);
        } catch (Exception ex) {
            log.warn("⚠️ [CFDI Express] Factura timbrada pero descarga inmediata de '{}' falló: {}", url,
                    ex.getMessage());
            return null;
        }
    }

    private HttpResponse<String> sendString(HttpRequest request, String actionDesc) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CfdiIntegrationException("Se interrumpió la comunicación al " + actionDesc + ".", e);
        } catch (Exception e) {
            throw new CfdiIntegrationException("Error de conexión al " + actionDesc + ": " + e.getMessage(), e);
        }
    }

    private void ensureApiKeyConfigured() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new CfdiIntegrationException(
                    "CFDI_EXPRESS_API_KEY no está configurada en el servidor.");
        }
    }

    private String extractFirstMerchantId(String jsonBody) {
        if (jsonBody == null || jsonBody.isBlank()) {
            return null;
        }
        JsonElement root = JsonParser.parseString(jsonBody);
        if (root.isJsonArray()) {
            return extractIdFromArray(root.getAsJsonArray());
        }
        if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("data") && obj.get("data").isJsonArray()) {
                return extractIdFromArray(obj.getAsJsonArray("data"));
            }
            if (obj.has("merchants") && obj.get("merchants").isJsonArray()) {
                return extractIdFromArray(obj.getAsJsonArray("merchants"));
            }
            return getString(obj, "id");
        }
        return null;
    }

    private String extractIdFromArray(JsonArray arr) {
        for (JsonElement element : arr) {
            if (element != null && element.isJsonObject()) {
                String id = getString(element.getAsJsonObject(), "id");
                if (id != null && !id.isBlank()) {
                    return id;
                }
            }
        }
        return null;
    }

    private ParsedInvoiceResponse parseInvoiceResponse(String jsonBody) {
        if (jsonBody == null || jsonBody.isBlank()) {
            throw new CfdiIntegrationException("CFDI Express devolvió una respuesta vacía.");
        }
        JsonObject root = JsonParser.parseString(jsonBody).getAsJsonObject();
        JsonObject obj = (root.has("data") && root.get("data").isJsonObject())
                ? root.getAsJsonObject("data")
                : root;
        return parseInvoiceObject(obj);
    }

    private ParsedInvoiceResponse parseInvoiceObject(JsonObject obj) {
        String id = getString(obj, "id");
        String status = getString(obj, "status");
        String uuid = getString(obj, "uuid");
        String filesStatus = null;
        String pdfUrl = null;
        String xmlUrl = null;

        if (obj.has("files") && obj.get("files").isJsonObject()) {
            JsonObject files = obj.getAsJsonObject("files");
            filesStatus = getString(files, "status");
            pdfUrl = nonBlankOrDefault(getString(files, "pdfUrl"), getString(files, "pdf_url"));
            xmlUrl = nonBlankOrDefault(getString(files, "xmlUrl"), getString(files, "xml_url"));
        }
        if (pdfUrl == null) {
            pdfUrl = nonBlankOrDefault(getString(obj, "pdfUrl"), getString(obj, "pdf_url"));
        }
        if (xmlUrl == null) {
            xmlUrl = nonBlankOrDefault(getString(obj, "xmlUrl"), getString(obj, "xml_url"));
        }

        return new ParsedInvoiceResponse(id, uuid, status, filesStatus, pdfUrl, xmlUrl);
    }

    private String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "Sin detalle de error.";
        }
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed.isJsonObject()) {
                JsonObject obj = parsed.getAsJsonObject();
                String detail = getString(obj, "detail");
                if (detail != null && !detail.isBlank()) {
                    return detail;
                }
                String msg = getString(obj, "message");
                if (msg != null && !msg.isBlank()) {
                    return msg;
                }
                if (obj.has("error")) {
                    JsonElement errEl = obj.get("error");
                    if (errEl.isJsonPrimitive()) {
                        return errEl.getAsString();
                    }
                    if (errEl.isJsonObject()) {
                        String nested = nonBlankOrDefault(
                                getString(errEl.getAsJsonObject(), "message"),
                                getString(errEl.getAsJsonObject(), "detail")
                        );
                        if (nested != null && !nested.isBlank()) {
                            return nested;
                        }
                    }
                }
                String title = getString(obj, "title");
                if (title != null && !title.isBlank()) {
                    return title;
                }
            }
        } catch (Exception ignored) {
            // fallback a cuerpo plano
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    private String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private String nonBlankOrDefault(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value.trim() : fallback;
    }

    private String normalizeBaseUrl(String raw) {
        String url = (raw != null && !raw.isBlank()) ? raw.trim() : "https://api.cfdi.express/v1";
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private String resolveUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        String trimmed = rawUrl.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        if (trimmed.startsWith("/v1/")) {
            String host = baseUrl.endsWith("/v1") ? baseUrl.substring(0, baseUrl.length() - 3) : baseUrl;
            return host + trimmed;
        }
        return trimmed.startsWith("/") ? baseUrl + trimmed : baseUrl + "/" + trimmed;
    }

    private boolean isSameApiHost(String url) {
        try {
            URI baseUri = URI.create(baseUrl);
            URI targetUri = URI.create(url);
            return baseUri.getHost() != null && baseUri.getHost().equalsIgnoreCase(targetUri.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    private record ParsedInvoiceResponse(
            String cfdiExpressId,
            String uuid,
            String status,
            String filesStatus,
            String pdfUrl,
            String xmlUrl) {
    }
}
