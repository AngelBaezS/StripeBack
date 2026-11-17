package org.angel.paymentclean.infrastructure.adapters.out.cfdi;

import com.sun.net.httpserver.HttpServer;
import org.angel.paymentclean.domain.exception.ArticleBusinessConflictException;
import org.angel.paymentclean.domain.exception.CfdiIntegrationException;
import org.angel.paymentclean.domain.port.out.CfdiExpressPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CfdiExpressServiceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void stampInvoice_shouldAutoResolveMerchantAndSendPricesIncludeTaxWithIdempotencyKey() throws Exception {
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        AtomicReference<String> capturedIdempotency = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        String baseUrl = "http://localhost:" + port + "/v1";

        server.createContext("/v1/merchants", exchange -> {
            byte[] resp = "{\"object\":\"list\",\"data\":[{\"id\":\"mer_auto_123\",\"rfc\":\"EKU9003173C9\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });

        server.createContext("/v1/invoices", exchange -> {
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capturedIdempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String json = """
                    {
                      "id": "inv_test_900",
                      "status": "stamped",
                      "uuid": "F47AC10B-58CC-4372-A567-0E02B2C3D479",
                      "files": {
                        "status": "ready",
                        "pdfUrl": "http://localhost:%d/files/inv_test_900.pdf",
                        "xmlUrl": "http://localhost:%d/files/inv_test_900.xml"
                      }
                    }
                    """.formatted(port, port);
            byte[] resp = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });

        server.createContext("/files/inv_test_900.pdf", exchange -> {
            byte[] bytes = "%PDF-1.4\nTEST".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        server.createContext("/files/inv_test_900.xml", exchange -> {
            byte[] bytes = "<cfdi:Comprobante Version=\"4.0\"/>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        server.start();

        CfdiExpressService service = new CfdiExpressService(
                baseUrl,
                "sk_test_cfdi_key",
                "",
                "01010101",
                "H87"
        );

        CfdiExpressPort.CfdiStampResult result = service.stampInvoice(new CfdiExpressPort.CfdiStampCommand(
                "ord_cfdi_1",
                "xia190128j61",
                "Xenon Industrial SA",
                "01160",
                "601",
                "G03",
                "04",
                "PUE",
                List.of(new CfdiExpressPort.CfdiInvoiceLineItem(
                        "prd_mouse_1", "01010101", "H87", "Mouse Gamer", 1L, new BigDecimal("800.00"), 0.16d
                ))
        ));

        assertEquals("inv_test_900", result.cfdiExpressId());
        assertEquals("F47AC10B-58CC-4372-A567-0E02B2C3D479", result.uuid());
        assertEquals("Bearer sk_test_cfdi_key", capturedAuth.get());
        assertTrue(capturedIdempotency.get().startsWith("cfdi-ord_cfdi_1-"));
        assertTrue(capturedBody.get().contains("\"merchantId\":\"mer_auto_123\""));
        assertTrue(capturedBody.get().contains("\"pricesIncludeTax\":true"));
        assertTrue(capturedBody.get().contains("\"lineDecimals\":6"));
        assertTrue(capturedBody.get().contains("\"currency\":\"MXN\""));
        assertTrue(capturedBody.get().contains("\"folio\":\"ord_cfdi_1\""));
        assertTrue(capturedBody.get().contains("\"noIdentificacion\":\"prd_mouse_1\""));
        assertTrue(capturedBody.get().contains("\"ivaRate\":0.16"));
        assertNotNull(result.pdfBytes());
        assertNotNull(result.xmlBytes());
    }

    @Test
    void stampInvoice_when202StillStamping_shouldPollGetInvoiceUntilStampedAndFilesReady() throws Exception {
        AtomicInteger getCalls = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        String baseUrl = "http://localhost:" + port + "/v1";

        server.createContext("/v1/invoices", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                String json = """
                        {
                          "id": "inv_202_1",
                          "status": "stamping",
                          "uuid": null,
                          "files": { "status": "pending" }
                        }
                        """;
                byte[] resp = json.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(202, resp.length);
                exchange.getResponseBody().write(resp);
                exchange.close();
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/inv_202_1")) {
                    int call = getCalls.incrementAndGet();
                    String json = call == 1
                            ? "{\"id\":\"inv_202_1\",\"status\":\"stamping\",\"uuid\":null,\"files\":{\"status\":\"processing\"}}"
                            : """
                              {
                                "id": "inv_202_1",
                                "status": "stamped",
                                "uuid": "UUID-202-READY",
                                "files": {
                                  "status": "ready",
                                  "pdfUrl": "http://localhost:%d/files/202.pdf",
                                  "xmlUrl": "http://localhost:%d/files/202.xml"
                                }
                              }
                              """.formatted(port, port);
                    byte[] resp = json.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, resp.length);
                    exchange.getResponseBody().write(resp);
                    exchange.close();
                    return;
                }
                byte[] listResp = """
                        {
                          "object": "list",
                          "data": [{"id":"inv_202_1","status":"stamped","uuid":"UUID-202-READY","files":{"status":"ready"}}],
                          "has_more": false
                        }
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, listResp.length);
                exchange.getResponseBody().write(listResp);
                exchange.close();
            }
        });
        server.createContext("/v1/invoices/inv_202_1/cancel", exchange -> {
            byte[] resp = "{\"id\":\"inv_202_1\",\"status\":\"cancel_pending\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.createContext("/files/202.pdf", exchange -> {
            byte[] bytes = "%PDF-1.4\n202".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/files/202.xml", exchange -> {
            byte[] bytes = "<cfdi:Comprobante/>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        CfdiExpressService service = new CfdiExpressService(
                baseUrl, "sk_test_cfdi_key", "mer_direct", "01010101", "H87"
        );

        CfdiExpressPort.CfdiStampResult result = service.stampInvoice(new CfdiExpressPort.CfdiStampCommand(
                "ord_202", "XIA190128J61", "XENON", "01160", "601", "G03", "04", "PUE",
                List.of(new CfdiExpressPort.CfdiInvoiceLineItem("prd_1", "01010101", "H87", "Item", 1L, BigDecimal.TEN, 0.16))
        ));

        assertEquals("UUID-202-READY", result.uuid());
        assertEquals("stamped", result.status());
        assertEquals(2, getCalls.get());
        assertEquals(1, service.listInvoicesByQuery("ord_202", "mer_direct").size());
        assertEquals("cancel_pending", service.cancelInvoice("inv_202_1", "02", null).status());
    }

    @Test
    void stampInvoice_whenStatusIsStampFailed_shouldThrowCfdiIntegrationException() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/v1/invoices", exchange -> {
            byte[] resp = "{\"id\":\"inv_fail\",\"status\":\"stamp_failed\",\"files\":{\"status\":\"failed\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();

        CfdiExpressService service = new CfdiExpressService(
                "http://localhost:" + port + "/v1", "sk_test_cfdi_key", "mer_direct", "01010101", "H87"
        );

        assertThrows(CfdiIntegrationException.class, () -> service.stampInvoice(
                new CfdiExpressPort.CfdiStampCommand(
                        "ord_fail", "XIA190128J61", "XENON", "01160", "601", "G03", "04", "PUE",
                        List.of(new CfdiExpressPort.CfdiInvoiceLineItem("01010101", "H87", "Item", 1L, BigDecimal.TEN, 0.16))
                )
        ));
    }

    @Test
    void stampInvoice_whenNoMerchantExistsYet_shouldThrowClearConflictError() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/v1/merchants", exchange -> {
            byte[] resp = "{\"object\":\"list\",\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();

        CfdiExpressService service = new CfdiExpressService(
                "http://localhost:" + port + "/v1",
                "sk_test_cfdi_key",
                "",
                "01010101",
                "H87"
        );

        assertThrows(ArticleBusinessConflictException.class, () -> service.stampInvoice(
                new CfdiExpressPort.CfdiStampCommand(
                        "ord_1", "XAXX010101000", "PUBLICO EN GENERAL", "06000", "616", "S01",
                        "04", "PUE",
                        List.of(new CfdiExpressPort.CfdiInvoiceLineItem("01010101", "H87", "Item", 1L, BigDecimal.TEN, 0.16))
                )
        ));
    }

    @Test
    void stampInvoice_whenGenericRfcPublicoEnGeneral_shouldEnforceEmitterZipRegimen616UsoS01AndInformacionGlobal() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/v1/invoices", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            String json = """
                    {
                      "id": "inv_global_1",
                      "status": "stamped",
                      "uuid": "UUID-GLOBAL-1",
                      "files": { "status": "ready" }
                    }
                    """;
            byte[] resp = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders("POST".equals(exchange.getRequestMethod()) ? 201 : 200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();

        CfdiExpressService service = new CfdiExpressService(
                java.net.http.HttpClient.newHttpClient(),
                "http://localhost:" + port + "/v1",
                "sk_test_cfdi_key",
                "mer_direct",
                "01010101",
                "H87",
                "42501"
        );

        CfdiExpressPort.CfdiStampResult result = service.stampInvoice(new CfdiExpressPort.CfdiStampCommand(
                "ord_global", "XAXX010101000", "PÚBLICO EN GENERAL", "99999", "612", "G03",
                "04", "PUE",
                List.of(new CfdiExpressPort.CfdiInvoiceLineItem("prd_1", "01010101", "H87", "Item", 1L, BigDecimal.TEN, 0.16))
        ));

        assertEquals("UUID-GLOBAL-1", result.uuid());
        String body = capturedBody.get();
        assertTrue(body.contains("\"usoCfdi\":\"S01\""));
        assertTrue(body.contains("\"zip\":\"42501\""));
        assertTrue(body.contains("\"regimenFiscal\":\"616\""));
        assertTrue(body.contains("\"name\":\"PUBLICO EN GENERAL\""));
        assertTrue(body.contains("\"informacionGlobal\""));
        assertTrue(body.contains("\"periodicidad\":\"01\""));
    }

    @Test
    void stampInvoice_whenProblemJsonWithDetail_shouldExposeReadableDetailMessage() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/v1/invoices", exchange -> {
            byte[] resp = "{\"title\":\"Validation error\",\"status\":400,\"detail\":\"El CP no coincide con el RFC\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();

        CfdiExpressService service = new CfdiExpressService(
                "http://localhost:" + port + "/v1", "sk_test_cfdi_key", "mer_direct", "01010101", "H87"
        );

        CfdiIntegrationException ex = assertThrows(CfdiIntegrationException.class, () -> service.stampInvoice(
                new CfdiExpressPort.CfdiStampCommand(
                        "ord_bad", "XIA190128J61", "XENON", "01160", "601", "G03", "04", "PUE",
                        List.of(new CfdiExpressPort.CfdiInvoiceLineItem("01010101", "H87", "Item", 1L, BigDecimal.TEN, 0.16))
                )
        ));
        assertTrue(ex.getMessage().contains("El CP no coincide con el RFC"));
    }

    @Test
    void stampInvoice_whenGenericRfcAndStaleEmitterZip_shouldAutoDiscoverMerchantZipFromCfdiExpress() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/v1/merchants/mer_live_zip", exchange -> {
            byte[] resp = "{\"id\":\"mer_live_zip\",\"zip\":\"42501\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });

        server.createContext("/v1/invoices", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] resp = """
                    {
                      "id": "inv_glob_zip",
                      "status": "stamped",
                      "uuid": "UUID-GLOB-ZIP",
                      "files": { "status": "ready" }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders("POST".equals(exchange.getRequestMethod()) ? 201 : 200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();

        CfdiExpressService service = new CfdiExpressService(
                java.net.http.HttpClient.newHttpClient(),
                "http://localhost:" + port + "/v1",
                "sk_test_cfdi_key",
                "mer_live_zip",
                "01010101",
                "H87",
                "62661"
        );

        CfdiExpressPort.CfdiStampResult result = service.stampInvoice(new CfdiExpressPort.CfdiStampCommand(
                "ord_glob_zip", "XAXX010101000", "PUBLICO EN GENERAL", "62661", "616", "S01",
                "04", "PUE",
                List.of(new CfdiExpressPort.CfdiInvoiceLineItem("prd_1", "01010101", "H87", "Item", 1L, BigDecimal.TEN, 0.16))
        ));

        assertEquals("UUID-GLOB-ZIP", result.uuid());
        assertTrue(capturedBody.get().contains("\"zip\":\"42501\""));
    }
}
