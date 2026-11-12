package com.mythix.tcg.order.application;

import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.domain.OrderItem;
import com.mythix.tcg.user.domain.User;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InvoicePdfServiceTest {

    @Test
    void generate_createsReadableMultiPageInvoiceWithVerificationInstructions() throws Exception {
        User buyer = User.builder().id(1L).fullName("Ash Ketchum").email("ash@example.com").build();
        User seller = User.builder().id(2L).fullName("Poke Master").email("seller@example.com").build();
        Order order = Order.builder()
                .id(25L)
                .orderNumber("ORD-US25-001")
                .orderType("DIRECT")
                .buyer(buyer)
                .status("FULFILLED")
                .subtotalAmount(new BigDecimal("4400.00"))
                .discountAmount(new BigDecimal("200.00"))
                .totalAmount(new BigDecimal("4200.00"))
                .createdAt(LocalDateTime.of(2026, 11, 11, 14, 30))
                .updatedAt(LocalDateTime.of(2026, 11, 11, 15, 0))
                .items(new ArrayList<>())
                .build();

        List<OrderItem> items = order.getItems();
        for (int index = 1; index <= 22; index++) {
            items.add(OrderItem.builder()
                    .id((long) index)
                    .order(order)
                    .seller(seller)
                    .cardId("card-" + index)
                    .productNameSnapshot("Pokemon Card " + index)
                    .shopNameSnapshot("Poke Master Store")
                    .priceAtPurchase(new BigDecimal("200.00"))
                    .quantity(1)
                    .build());
        }

        InvoicePdfService.InvoiceFile invoice = new InvoicePdfService().generate(
                order,
                "https://tcgmythix.store/orders/verify/ORD-US25-001");

        assertThat(invoice.filename()).isEqualTo("mythix-order-ORD-US25-001.pdf");
        assertThat(invoice.content()).startsWith("%PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        Path output = Path.of("target", "test-output", "us25-invoice.pdf");
        Files.createDirectories(output.getParent());
        Files.write(output, invoice.content());

        try (var document = Loader.loadPDF(invoice.content())) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(document);
            assertThat(text)
                    .contains("MYTHIX")
                    .contains("ORD-US25-001")
                    .contains("Pokemon Card 22")
                    .contains("THB 4200.00")
                    .contains("VERIFY THIS ORDER");
        }
    }
}
