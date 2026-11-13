package com.mythix.tcg.notification.application;

import com.mythix.tcg.notification.domain.NotificationEmail;
import com.mythix.tcg.notification.persistence.NotificationEmailRepository;
import com.mythix.tcg.order.application.InvoicePdfService;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.persistence.OrderRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.Multipart;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationEmailDeliveryWorkerTest {
    private final NotificationEmailRepository outbox = mock(NotificationEmailRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final SensitiveMailCipher cipher = new SensitiveMailCipher(new com.mythix.tcg.auth.security.JwtProperties("test-secret-with-at-least-32-bytes", 60));
    private final NotificationEmailDeliveryWorker worker = new NotificationEmailDeliveryWorker(
        outbox, orders, new InvoicePdfService(), sender, cipher, new BrandedEmailRenderer());

    @Test
    void sensitiveRecoveryBodyIsDecryptedForMimeDelivery() throws Exception {
        NotificationEmail email = new NotificationEmail();
        email.setId(91L);
        email.setRecipientEmail("buyer@example.test");
        email.setSubject("Reset");
        email.setSensitive(true);
        email.setBody(cipher.encrypt("https://example.test/reset?token=secret"));
        when(outbox.findById(91L)).thenReturn(Optional.of(email));
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        worker.deliver(91L, "orders@example.test");
        message.saveChanges();
        Multipart alternatives = findAlternatives((Multipart) message.getContent());
        assertThat(alternatives.getCount()).isEqualTo(2);
        assertThat(alternatives.getBodyPart(0).getContent().toString()).contains("token=secret");
        assertThat(alternatives.getBodyPart(1).getContent().toString()).contains("Reset password", "token=secret");
        verify(outbox).markSent(eq(91L), any());
    }

    @Test
    void bothSensitiveAuthenticationEmailsHavePlainAndBrandedHtmlAlternatives() throws Exception {
        for (String[] sample : new String[][] {
            {"Mythix: Reset your password", "Use this link to reset your password within 30 minutes:\n\nhttps://example.test/reset?token=secret\n\nIf you did not request this, you can ignore this email."},
            {"Mythix: Your password was changed", "Your Mythix password was changed. If this was not you, contact support immediately."}
        }) {
            NotificationEmail email = new NotificationEmail();
            email.setId(92L);
            email.setRecipientEmail("buyer@example.test");
            email.setSubject(sample[0]);
            email.setSensitive(true);
            email.setBody(cipher.encrypt(sample[1]));
            when(outbox.findById(92L)).thenReturn(Optional.of(email));
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
            when(sender.createMimeMessage()).thenReturn(message);

            worker.deliver(92L, "orders@example.test");

            message.saveChanges();
            Multipart alternatives = findAlternatives((Multipart) message.getContent());
            assertThat(alternatives.getCount()).isEqualTo(2);
            assertThat(alternatives.getBodyPart(0).getContent().toString()).contains("password");
            assertThat(alternatives.getBodyPart(1).getContent().toString()).contains("MYTHIX", "password");
            if (sample[0].contains("Reset")) assertThat(alternatives.getBodyPart(1).getContent().toString()).contains("href=\"https://example.test/reset?token=secret\"");
        }
    }

    @Test
    void shipmentEmailAttachesGeneratedSummaryWithStatusSnapshotAndVerificationQr() throws Exception {
        NotificationEmail email = new NotificationEmail();
        email.setId(3L);
        email.setNotificationId(88L);
        email.setRecipientEmail("buyer@example.test");
        email.setSubject("Order sent");
        email.setBody("Tracking: EMS-123\nView details: https://tcgmythix.store/orders/9");
        email.setAttachmentOrderId(9L);
        email.setAttachmentType("application/pdf");
        email.setAttachmentStatusSnapshot("SENT");
        when(outbox.findById(3L)).thenReturn(Optional.of(email));
        when(orders.findForEmailSummary(9L)).thenReturn(Optional.of(Order.builder()
            .id(9L).orderNumber("ORD-9").status("PENDING").orderType("AUCTION").build()));
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);

        worker.deliver(3L, "orders@mythix.test");

        message.saveChanges();
        assertThat(message.getContentType()).contains("multipart/mixed");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("buyer@example.test");
        Multipart parts = (Multipart) message.getContent();
        assertThat(parts.getCount()).isEqualTo(2);
        Multipart alternatives = findAlternatives((Multipart) parts.getBodyPart(0).getContent());
        assertThat(alternatives.getCount()).isEqualTo(2);
        assertThat(alternatives.getBodyPart(0).getContent().toString()).contains("Tracking: EMS-123");
        assertThat(alternatives.getBodyPart(1).getContent().toString()).contains("MYTHIX", "EMS-123");
        assertThat(parts.getBodyPart(1).getFileName()).isEqualTo("mythix-order-ORD-9.pdf");
        assertThat(parts.getBodyPart(1).getContentType()).contains("application/pdf");
        byte[] pdf = parts.getBodyPart(1).getInputStream().readAllBytes();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
        String pdfText = new PDFTextStripper().getText(Loader.loadPDF(pdf));
        assertThat(pdfText).contains("SENT").doesNotContain("PENDING");
        verify(outbox).markSent(eq(3L), any());
    }

    @Test
    void oversizedSummaryIsRejectedBeforeEmailSend() {
        NotificationEmail email = new NotificationEmail();
        email.setId(4L);
        email.setNotificationId(99L);
        email.setRecipientEmail("buyer@example.test");
        email.setSubject("Order sent");
        email.setBody("Tracking: EMS-123");
        email.setAttachmentOrderId(9L);
        email.setAttachmentType("application/pdf");
        email.setAttachmentStatusSnapshot("SENT");
        when(outbox.findById(4L)).thenReturn(Optional.of(email));
        when(orders.findForEmailSummary(9L)).thenReturn(Optional.of(Order.builder().id(9L).orderNumber("ORD-9").build()));
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        InvoicePdfService oversizedPdf = mock(InvoicePdfService.class);
        when(oversizedPdf.generate(any(), eq("SENT"), anyString()))
            .thenReturn(new InvoicePdfService.InvoiceFile(new byte[5 * 1024 * 1024 + 1], "oversize.pdf"));
        NotificationEmailDeliveryWorker oversizeWorker = new NotificationEmailDeliveryWorker(outbox, orders, oversizedPdf, sender, cipher, new BrandedEmailRenderer());

        org.junit.jupiter.api.Assertions.assertThrows(NotificationEmailAttachmentTooLargeException.class,
            () -> oversizeWorker.deliver(4L, "orders@mythix.test"));
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    void invalidAttachmentMetadataIsRejectedBeforeEmailSend() {
        NotificationEmail email = new NotificationEmail();
        email.setId(5L);
        email.setNotificationId(100L);
        email.setRecipientEmail("buyer@example.test");
        email.setSubject("Order sent");
        email.setBody("Tracking: EMS-123");
        email.setAttachmentOrderId(9L);
        email.setAttachmentType("text/html");
        email.setAttachmentStatusSnapshot("SENT");
        when(outbox.findById(5L)).thenReturn(Optional.of(email));

        org.junit.jupiter.api.Assertions.assertThrows(NotificationEmailPermanentFailureException.class,
            () -> worker.deliver(5L, "orders@mythix.test"));
        verifyNoInteractions(orders);
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    void verificationQrUsesConfiguredPublicBaseUrl() throws Exception {
        NotificationEmail email = new NotificationEmail();
        email.setId(6L);
        email.setNotificationId(101L);
        email.setRecipientEmail("buyer@example.test");
        email.setSubject("Order sent");
        email.setBody("Tracking: EMS-123");
        email.setAttachmentOrderId(9L);
        email.setAttachmentType("application/pdf");
        email.setAttachmentStatusSnapshot("SENT");
        when(outbox.findById(6L)).thenReturn(Optional.of(email));
        Order order = Order.builder().id(9L).orderNumber("ORD 9").build();
        when(orders.findForEmailSummary(9L)).thenReturn(Optional.of(order));
        InvoicePdfService pdf = mock(InvoicePdfService.class);
        when(pdf.generate(eq(order), eq("SENT"), anyString()))
            .thenReturn(new InvoicePdfService.InvoiceFile("%PDF".getBytes(), "summary.pdf"));
        NotificationEmailDeliveryWorker configuredWorker = new NotificationEmailDeliveryWorker(outbox, orders, pdf, sender, cipher, new BrandedEmailRenderer());
        ReflectionTestUtils.setField(configuredWorker, "baseUrl", "https://tcgmythix.store/");
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        configuredWorker.deliver(6L, "orders@mythix.test");

        verify(pdf).generate(order, "SENT", "https://tcgmythix.store/orders/verify/ORD%209");
        verify(sender).send(any(MimeMessage.class));
    }

    @Test
    void htmlRendererEscapesDynamicContentAndOnlyLinksSafeHttpUrls() {
        BrandedEmailRenderer renderer = new BrandedEmailRenderer();
        String html = renderer.render("Mythix: <Order>", "Hello <script>alert(1)</script>\n\nView details: javascript:alert(1)");

        assertThat(html).contains("Mythix: &lt;Order&gt;", "&lt;script&gt;alert(1)&lt;/script&gt;", "IBM Plex Sans Thai")
            .doesNotContain("<script>", "href=\"javascript:");
        String resetHtml = renderer.render("Mythix: Reset your password", "Use this link:\n\nhttps://example.test/reset?token=secret");
        assertThat(resetHtml).contains("Security", "href=\"https://example.test/reset?token=secret\"")
            .doesNotContain(">https://example.test/reset?token=secret<", "Reset password link:");
        assertThat(renderer.render("Mythix: Your order was cancelled", "Cancelled"))
            .contains("Order update", "#FFA5A5");
    }

    @Test
    void stockEmailVariantsUseTheirSeverityAccentAndSpecificCta() {
        BrandedEmailRenderer renderer = new BrandedEmailRenderer();
        String available = renderer.render("Mythix: Stock available", "A card you follow is available.\n\nView details: https://mythix.test/cards/card-1");
        String critical = renderer.render("Mythix: Critical stock alert", "A listing needs attention.\n\nView details: https://mythix.test/workspace/stock-forecast?search=Card");
        String outOfStock = renderer.render("Mythix: Listing out of stock", "Your listing has no stock.\n\nView details: https://mythix.test/workspace/listings?search=Card");

        assertThat(available).contains("Stock update", "#4CC987", "View card", "href=\"https://mythix.test/cards/card-1\"");
        assertThat(critical).contains("Inventory alert", "#F2B84B", "View stock forecast", "href=\"https://mythix.test/workspace/stock-forecast?search=Card\"");
        assertThat(outOfStock).contains("Inventory alert", "#FFA5A5", "Manage listing", "href=\"https://mythix.test/workspace/listings?search=Card\"");
    }

    @Test
    void stockTemplatesRequireAnExactKnownSubject() {
        String html = new BrandedEmailRenderer().render("Mythix: Your stock available reminder", "Card update\n\nView details: https://mythix.test/cards/card-1");

        assertThat(html).contains("Mythix update", "#72A8FF", "View details")
            .doesNotContain("Stock update", "#4CC987", "View card");
    }

    @Test
    void deliveryUsesTheRenderingInterfaceForHtmlAlternative() throws Exception {
        EmailContentRenderer renderer = mock(EmailContentRenderer.class);
        NotificationEmailDeliveryWorker interfaceWorker = new NotificationEmailDeliveryWorker(
            outbox, orders, new InvoicePdfService(), sender, cipher, renderer);
        NotificationEmail email = new NotificationEmail();
        email.setId(93L);
        email.setRecipientEmail("buyer@example.test");
        email.setSubject("Stock");
        email.setBody("Plain stock update");
        when(outbox.findById(93L)).thenReturn(Optional.of(email));
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        when(renderer.render("Stock", "Plain stock update")).thenReturn("<html>rendered alternative</html>");

        interfaceWorker.deliver(93L, "orders@example.test");

        message.saveChanges();
        Multipart alternatives = findAlternatives((Multipart) message.getContent());
        verify(renderer).render("Stock", "Plain stock update");
        assertThat(alternatives.getBodyPart(1).getContent().toString()).contains("rendered alternative");
        verify(sender).send(any(MimeMessage.class));
    }

    private Multipart findAlternatives(Multipart multipart) throws Exception {
        if (multipart.getContentType().toLowerCase().contains("multipart/alternative")) return multipart;
        for (int i = 0; i < multipart.getCount(); i++) {
            Object content = multipart.getBodyPart(i).getContent();
            if (content instanceof Multipart nested) {
                try {
                    return findAlternatives(nested);
                } catch (AssertionError ignored) {
                    // Continue through related/mixed containers until the alternative body is found.
                }
            }
        }
        throw new AssertionError("No multipart/alternative body found");
    }
}
