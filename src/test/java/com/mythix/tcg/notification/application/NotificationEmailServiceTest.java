package com.mythix.tcg.notification.application;

import com.mythix.tcg.notification.domain.NotificationEmail;
import com.mythix.tcg.notification.domain.UserNotification;
import com.mythix.tcg.notification.persistence.NotificationEmailRepository;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.auth.security.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationEmailServiceTest {
    private final NotificationEmailRepository outbox = mock(NotificationEmailRepository.class);
    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final NotificationEmailService service = new NotificationEmailService(outbox, sender,
        new SensitiveMailCipher(new JwtProperties("test-secret-with-at-least-32-bytes", 60)));

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "from", "orders@mythix.test");
        ReflectionTestUtils.setField(service, "baseUrl", "https://mythix.test");
    }

    @Test
    void sentOrderEmailContainsStructuredTrackingAndLink() {
        Order order = Order.builder().id(9L).orderNumber("ORD-9")
            .trackingNumber("EMS123456").shippingCarrier("Thailand Post").build();
        UserNotification notification = UserNotification.builder().recipient(User.builder().email("buyer@test.com").build())
            .order(order).type("ORDER_SENT_BUYER").title("Your order has been sent")
            .message("The seller sent your order.").actionPath("/orders/9").build();
        notification.setId(17L);

        service.enqueue(notification);

        var captured = org.mockito.ArgumentCaptor.forClass(NotificationEmail.class);
        verify(outbox).save(captured.capture());
        assertThat(captured.getValue().getRecipientEmail()).isEqualTo("buyer@test.com");
        assertThat(captured.getValue().getNotificationId()).isEqualTo(17L);
        assertThat(captured.getValue().getBody()).contains("EMS123456", "Thailand Post", "https://mythix.test/orders/9");
    }

    @Test
    void routineOrderConfirmationDoesNotQueueEmail() {
        UserNotification notification = UserNotification.builder().recipient(User.builder().email("buyer@test.com").build())
            .type("ORDER_CONFIRMED_BUYER").title("Confirmed").message("Confirmed").build();
        notification.setId(18L);
        service.enqueue(notification);
        verifyNoInteractions(outbox);
    }

    @Test
    void dueEmailIsClaimedBeforeSendingAndMarkedSent() {
        NotificationEmail email = new NotificationEmail();
        email.setId(4L);
        email.setNotificationId(17L);
        email.setRecipientEmail("buyer@test.com");
        email.setSubject("Order sent");
        email.setBody("Tracking: EMS123456");
        when(outbox.findDue(any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of(email));
        when(outbox.claim(eq(4L), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);

        service.deliverDue();

        var message = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        assertThat(message.getValue().getTo()).containsExactly("buyer@test.com");
        verify(outbox).markSent(eq(4L), any(LocalDateTime.class));
    }

    @Test
    void sensitiveEmailIsEncryptedAtRestAndDecryptedOnlyForSending() {
        service.enqueueSensitive("buyer@test.com", "Reset", "https://example.test/reset?token=secret", LocalDateTime.now().plusMinutes(5));
        var captured = org.mockito.ArgumentCaptor.forClass(NotificationEmail.class);
        verify(outbox).save(captured.capture());
        NotificationEmail email = captured.getValue();
        email.setId(10L);
        assertThat(email.getBody()).doesNotContain("secret", "https://");
        when(outbox.findDue(any(), any())).thenReturn(List.of(email));
        when(outbox.claim(eq(10L), any(), any())).thenReturn(1);
        service.deliverDue();
        var message = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        assertThat(message.getValue().getText()).contains("token=secret");
        verify(outbox).markSent(eq(10L), any());
    }

    @Test
    void expiredSensitiveMailIsDiscardedWithoutSending() {
        NotificationEmail email = new NotificationEmail();
        email.setId(11L);
        email.setSensitive(true);
        email.setExpiresAt(LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
        when(outbox.findDue(any(), any())).thenReturn(List.of(email));
        when(outbox.claim(eq(11L), any(), any())).thenReturn(1);
        service.deliverDue();
        verifyNoInteractions(sender);
        verify(outbox).markSent(eq(11L), any());
    }
}
