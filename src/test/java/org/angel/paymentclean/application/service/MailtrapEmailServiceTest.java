package org.angel.paymentclean.application.service;

import io.mailtrap.api.accounts.Accounts;
import io.mailtrap.api.inboxes.Inboxes;
import io.mailtrap.client.MailtrapClient;
import io.mailtrap.client.api.MailtrapEmailTestingApi;
import io.mailtrap.client.api.MailtrapGeneralApi;
import io.mailtrap.config.MailtrapConfig;
import io.mailtrap.model.request.emails.MailtrapMail;
import io.mailtrap.model.response.accounts.AccountsResponse;
import io.mailtrap.model.response.emails.SendResponse;
import io.mailtrap.model.response.inboxes.InboxResponse;
import org.angel.paymentclean.domain.enums.InvoiceStatus;
import org.angel.paymentclean.domain.enums.Status;
import org.angel.paymentclean.domain.model.Order;
import org.angel.paymentclean.domain.model.OrderInvoice;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MailtrapEmailServiceTest {

    @Test
    void sendInvoiceEmail_inSandboxModeWithoutInboxId_shouldAutoDiscoverInboxAndSendWithAttachments() {
        MailtrapClient discoveryClient = mock(MailtrapClient.class);
        MailtrapGeneralApi generalApi = mock(MailtrapGeneralApi.class);
        Accounts accountsApi = mock(Accounts.class);
        MailtrapEmailTestingApi testingApi = mock(MailtrapEmailTestingApi.class);
        Inboxes inboxesApi = mock(Inboxes.class);

        AccountsResponse account = new AccountsResponse();
        account.setId(99L);
        InboxResponse inbox = new InboxResponse();
        inbox.setId(4455L);
        inbox.setName("Sandbox Dev Inbox");

        when(discoveryClient.generalApi()).thenReturn(generalApi);
        when(generalApi.accounts()).thenReturn(accountsApi);
        when(accountsApi.getAllAccounts()).thenReturn(List.of(account));
        when(discoveryClient.testingApi()).thenReturn(testingApi);
        when(testingApi.inboxes()).thenReturn(inboxesApi);
        when(inboxesApi.getInboxes(99L)).thenReturn(List.of(inbox));

        MailtrapClient sendingClient = mock(MailtrapClient.class);
        SendResponse sendResponse = new SendResponse();
        sendResponse.setSuccess(true);
        sendResponse.setMessageIds(List.of("msg_1"));
        when(sendingClient.send(any(MailtrapMail.class))).thenReturn(sendResponse);

        MailtrapEmailService service = new MailtrapEmailService(
                "test-mailtrap-token",
                true,
                null,
                "hello@demomailtrap.com",
                "Pasarela de Pagos",
                cfg -> cfg.isSandbox() ? sendingClient : discoveryClient
        );

        Order order = new Order(
                "ord_10", "usr_1", "crt_1", "cus_1", "pi_1", null, null, null,
                116000L, "mxn", Status.PAID, List.of(), LocalDateTime.now(), null
        );
        OrderInvoice invoice = new OrderInvoice(
                "invo_1", "ord_10", "usr_1", "XIA190128J61", "XENON SA", "01160", "601", "G03",
                "cliente@correo.mx", "04", "inv_cfdi", "UUID-001", InvoiceStatus.STAMPED,
                "https://pdf", "https://xml", null, LocalDateTime.now(), LocalDateTime.now()
        );

        boolean sent = service.sendInvoiceEmail(invoice, order, new byte[]{1, 2, 3}, "<xml/>".getBytes());

        assertTrue(sent);
        ArgumentCaptor<MailtrapMail> mailCaptor = ArgumentCaptor.forClass(MailtrapMail.class);
        verify(sendingClient).send(mailCaptor.capture());
        MailtrapMail mail = mailCaptor.getValue();
        assertEquals("cliente@correo.mx", mail.getTo().get(0).getEmail());
        assertEquals(2, mail.getAttachments().size());
        assertEquals("factura-ord_10.pdf", mail.getAttachments().get(0).getFilename());
        assertEquals("factura-ord_10.xml", mail.getAttachments().get(1).getFilename());
    }

    @Test
    void sendInvoiceEmail_inEmailSendingModeWithOverrideTo_shouldUseNonSandboxConfigAndRedirectRecipient() {
        MailtrapClient sendingClient = mock(MailtrapClient.class);
        SendResponse sendResponse = new SendResponse();
        sendResponse.setSuccess(true);
        sendResponse.setMessageIds(List.of("msg_live_1"));
        when(sendingClient.send(any(MailtrapMail.class))).thenReturn(sendResponse);

        MailtrapConfig[] capturedConfig = new MailtrapConfig[1];
        MailtrapEmailService service = new MailtrapEmailService(
                "sending-token-123",
                false,
                null,
                "hello@demomailtrap.com",
                "Pasarela de Pagos",
                "angelbaezs1512@gmail.com",
                cfg -> {
                    capturedConfig[0] = cfg;
                    return sendingClient;
                }
        );

        Order order = new Order(
                "ord_11", "usr_1", "crt_1", "cus_1", "pi_1", null, null, null,
                116000L, "mxn", Status.PAID, List.of(), LocalDateTime.now(), null
        );
        OrderInvoice invoice = new OrderInvoice(
                "invo_2", "ord_11", "usr_1", "XIA190128J61", "XENON SA", "01160", "601", "G03",
                "otro-cliente@correo.mx", "04", "inv_cfdi_2", "UUID-002", InvoiceStatus.STAMPED,
                "https://pdf", "https://xml", null, LocalDateTime.now(), LocalDateTime.now()
        );

        boolean sent = service.sendInvoiceEmail(invoice, order, new byte[]{1, 2, 3}, "<xml/>".getBytes());

        assertTrue(sent);
        assertNotNull(capturedConfig[0]);
        assertFalse(capturedConfig[0].isSandbox());
        assertNull(capturedConfig[0].getInboxId());

        ArgumentCaptor<MailtrapMail> mailCaptor = ArgumentCaptor.forClass(MailtrapMail.class);
        verify(sendingClient).send(mailCaptor.capture());
        MailtrapMail mail = mailCaptor.getValue();
        assertEquals("hello@demomailtrap.com", mail.getFrom().getEmail());
        assertEquals("angelbaezs1512@gmail.com", mail.getTo().get(0).getEmail());
        assertEquals("cfdi-invoice", mail.getCategory());
        assertEquals(2, mail.getAttachments().size());
    }
}
