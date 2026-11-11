package com.mythix.tcg.notification.application;

import com.mythix.tcg.notification.domain.NotificationEmail;
import com.mythix.tcg.notification.persistence.NotificationEmailRepository;
import com.mythix.tcg.order.application.InvoicePdfService;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.persistence.OrderRepository;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class NotificationEmailDeliveryWorker {
    private static final int MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024;
    private final NotificationEmailRepository outbox;
    private final OrderRepository orders;
    private final InvoicePdfService invoicePdfService;
    private final JavaMailSender mailSender;

    @Value("${app.mail.base-url:http://localhost:5173}")
    private String baseUrl = "http://localhost:5173";

    @Transactional
    public void deliver(Long outboxId, String from) throws Exception {
        NotificationEmail email = outbox.findById(outboxId).orElseThrow();
        MimeMessage message = mailSender.createMimeMessage();
        boolean attachPdf = email.getAttachmentOrderId() != null;
        if (attachPdf && (!"application/pdf".equals(email.getAttachmentType())
                || !"SENT".equals(email.getAttachmentStatusSnapshot()))) {
            throw new NotificationEmailPermanentFailureException("Unsupported order summary attachment metadata");
        }
        MimeMessageHelper helper = new MimeMessageHelper(message, attachPdf, StandardCharsets.UTF_8.name());
        helper.setFrom(from);
        helper.setTo(email.getRecipientEmail());
        helper.setSubject(email.getSubject());
        String body = email.getBody();
        if (attachPdf) {
            Order order = orders.findForEmailSummary(email.getAttachmentOrderId())
                .orElseThrow(() -> new NotificationEmailPermanentFailureException("Order summary source was not found"));
            String verificationUrl = baseUrl.replaceAll("/+$", "") + "/orders/verify/"
                + URLEncoder.encode(order.getOrderNumber(), StandardCharsets.UTF_8).replace("+", "%20");
            var pdf = invoicePdfService.generate(order, email.getAttachmentStatusSnapshot(), verificationUrl);
            if (pdf.content().length > MAX_ATTACHMENT_BYTES) {
                throw new NotificationEmailAttachmentTooLargeException("Generated order summary PDF exceeds 5 MiB attachment limit");
            }
            String filename = "mythix-order-" + safeOrderNumber(order.getOrderNumber()) + ".pdf";
            helper.addAttachment(filename, new ByteArrayResource(pdf.content()), email.getAttachmentType());
        }
        helper.setText(body, false);
        mailSender.send(message);
        outbox.markSent(email.getId(), LocalDateTime.now(ZoneOffset.UTC));
    }

    private String safeOrderNumber(String orderNumber) {
        return orderNumber == null ? "order" : orderNumber.replaceAll("[^A-Za-z0-9_-]", "-");
    }
}
