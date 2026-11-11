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
    private final NotificationEmailDeliveryWorker worker = new NotificationEmailDeliveryWorker(
        outbox, orders, new InvoicePdfService(), sender);

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
        NotificationEmailDeliveryWorker oversizeWorker = new NotificationEmailDeliveryWorker(outbox, orders, oversizedPdf, sender);

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
        NotificationEmailDeliveryWorker configuredWorker = new NotificationEmailDeliveryWorker(outbox, orders, pdf, sender);
        ReflectionTestUtils.setField(configuredWorker, "baseUrl", "https://tcgmythix.store/");
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        configuredWorker.deliver(6L, "orders@mythix.test");

        verify(pdf).generate(order, "SENT", "https://tcgmythix.store/orders/verify/ORD%209");
        verify(sender).send(any(MimeMessage.class));
    }
}
