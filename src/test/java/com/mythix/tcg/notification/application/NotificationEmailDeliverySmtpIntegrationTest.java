package com.mythix.tcg.notification.application;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.mythix.tcg.auth.security.JwtProperties;
import com.mythix.tcg.notification.domain.NotificationEmail;
import com.mythix.tcg.notification.persistence.NotificationEmailRepository;
import com.mythix.tcg.order.application.InvoicePdfService;
import com.mythix.tcg.order.persistence.OrderRepository;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationEmailDeliverySmtpIntegrationTest {
    private final NotificationEmailRepository outbox = mock(NotificationEmailRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final SensitiveMailCipher cipher = new SensitiveMailCipher(new JwtProperties("test-secret-with-at-least-32-bytes", 60));
    private GreenMail smtp;
    private NotificationEmailDeliveryWorker worker;

    @BeforeEach
    void startEmbeddedSmtp() {
        smtp = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        smtp.start();

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(smtp.getSmtp().getPort());
        sender.setDefaultEncoding("UTF-8");
        Properties mailProperties = new Properties();
        mailProperties.setProperty("mail.smtp.auth", "false");
        mailProperties.setProperty("mail.smtp.connectiontimeout", "3000");
        mailProperties.setProperty("mail.smtp.timeout", "3000");
        sender.setJavaMailProperties(mailProperties);

        worker = new NotificationEmailDeliveryWorker(
            outbox,
            orders,
            new InvoicePdfService(),
            sender,
            cipher,
            new BrandedEmailRenderer());
    }

    @AfterEach
    void stopEmbeddedSmtp() {
        if (smtp != null) smtp.stop();
    }

    @Test
    void deliversCriticalStockEmailAsMimeAlternativesThroughEmbeddedSmtp() throws Exception {
        NotificationEmail email = new NotificationEmail();
        email.setId(326L);
        email.setRecipientEmail("seller@example.test");
        email.setSubject("Mythix: Critical stock alert");
        String sensitiveBody = "A listing needs attention.\n\nView details: https://mythix.test/workspace/stock-forecast?search=Charizard";
        email.setSensitive(true);
        email.setBody(cipher.encrypt(sensitiveBody));
        when(outbox.findById(326L)).thenReturn(Optional.of(email));

        worker.deliver(326L, "notifications@mythix.test");

        assertThat(smtp.waitForIncomingEmail(3000, 1)).isTrue();
        MimeMessage[] messages = smtp.getReceivedMessages();
        assertThat(messages).hasSize(1);
        MimeMessage received = messages[0];
        assertThat(received.getSubject()).isEqualTo("Mythix: Critical stock alert");
        assertThat(received.getFrom()[0].toString()).contains("notifications@mythix.test");
        assertThat(received.getRecipients(Message.RecipientType.TO)[0].toString()).contains("seller@example.test");

        Multipart alternatives = findAlternatives((Multipart) received.getContent());
        assertThat(alternatives.getContentType()).contains("multipart/alternative");
        assertThat(alternatives.getCount()).isEqualTo(2);
        String plain = alternatives.getBodyPart(0).getContent().toString();
        String html = alternatives.getBodyPart(1).getContent().toString();
        assertThat(plain).contains("A listing needs attention.", "https://mythix.test/workspace/stock-forecast?search=Charizard")
            .doesNotContain(email.getBody());
        assertThat(html).contains("Inventory alert", "View stock forecast", "href=\"https://mythix.test/workspace/stock-forecast?search=Charizard\"");
        verify(outbox).markSent(eq(326L), any());
    }

    private Multipart findAlternatives(Multipart multipart) throws Exception {
        if (multipart.getContentType().toLowerCase().contains("multipart/alternative")) return multipart;
        for (int i = 0; i < multipart.getCount(); i++) {
            Object content = multipart.getBodyPart(i).getContent();
            if (content instanceof Multipart nested) {
                try {
                    return findAlternatives(nested);
                } catch (AssertionError ignored) {
                    // Continue through the mixed container to the actual body alternative.
                }
            }
        }
        throw new AssertionError("No multipart/alternative body found");
    }
}
