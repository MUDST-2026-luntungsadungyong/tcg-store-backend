package com.mythix.tcg.order.application;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.domain.OrderItem;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
public class InvoicePdfService {

    private static final PDRectangle PAGE_SIZE = PDRectangle.A4;
    private static final float MARGIN = 44f;
    private static final float CONTENT_WIDTH = PAGE_SIZE.getWidth() - (MARGIN * 2);
    private static final Color NAVY = new Color(11, 19, 32);
    private static final Color BLUE = new Color(63, 128, 234);
    private static final Color GOLD = new Color(214, 168, 54);
    private static final Color SLATE = new Color(77, 91, 112);
    private static final Color LIGHT = new Color(239, 243, 249);
    private static final Color WHITE = Color.WHITE;
    private static final PDFont REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDFont BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH);

    public record InvoiceFile(byte[] content, String filename) {}

    public InvoiceFile generate(Order order, String verificationUrl) {
        return generate(order, order.getStatus(), verificationUrl);
    }

    public InvoiceFile generate(Order order, String statusSnapshot, String verificationUrl) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfCanvas canvas = new PdfCanvas(document);
            canvas.newPage();
            drawHeader(canvas, order, statusSnapshot);
            drawParties(canvas, order);
            drawItems(canvas, order.getItems());
            drawTotals(canvas, order);
            drawVerification(canvas, order, verificationUrl);
            canvas.closePage();
            document.save(output);
            return new InvoiceFile(output.toByteArray(), invoiceFilename(order));
        } catch (IOException | WriterException exception) {
            throw new IllegalStateException("Unable to generate invoice PDF", exception);
        }
    }

    private void drawHeader(PdfCanvas canvas, Order order, String statusSnapshot) throws IOException {
        float top = PAGE_SIZE.getHeight() - MARGIN;
        canvas.fillRect(MARGIN, top - 92, CONTENT_WIDTH, 92, NAVY);
        canvas.text("MYTHIX", MARGIN + 20, top - 34, BOLD, 22, WHITE);
        canvas.text("TRADING CARD MARKETPLACE", MARGIN + 20, top - 53, REGULAR, 8, new Color(185, 199, 220));
        canvas.textRight("ORDER SUMMARY", PAGE_SIZE.getWidth() - MARGIN - 20, top - 31, BOLD, 16, WHITE);
        canvas.textRight("#" + safe(order.getOrderNumber()), PAGE_SIZE.getWidth() - MARGIN - 20, top - 53, REGULAR, 10, GOLD);
        canvas.y = top - 116;

        canvas.text("ORDER STATUS", MARGIN, canvas.y, BOLD, 8, SLATE);
        canvas.text(safe(statusSnapshot), MARGIN, canvas.y - 18, BOLD, 12, BLUE);
        canvas.text("ORDER DATE", MARGIN + 170, canvas.y, BOLD, 8, SLATE);
        canvas.text(order.getCreatedAt() == null ? "-" : DATE_TIME.format(order.getCreatedAt()), MARGIN + 170, canvas.y - 18, REGULAR, 10, NAVY);
        canvas.text("ORDER TYPE", MARGIN + 350, canvas.y, BOLD, 8, SLATE);
        canvas.text(safe(order.getOrderType()), MARGIN + 350, canvas.y - 18, REGULAR, 10, NAVY);
        canvas.y -= 50;
        canvas.line(MARGIN, canvas.y, PAGE_SIZE.getWidth() - MARGIN, canvas.y, new Color(211, 220, 232));
        canvas.y -= 24;
    }

    private void drawParties(PdfCanvas canvas, Order order) throws IOException {
        OrderItem firstItem = order.getItems() == null || order.getItems().isEmpty() ? null : order.getItems().get(0);
        String buyerName = order.getBuyer() == null ? "-" : order.getBuyer().getFullName();
        String buyerEmail = order.getBuyer() == null ? "-" : order.getBuyer().getEmail();
        String sellerName = firstItem == null ? "-" : firstNonBlank(firstItem.getShopNameSnapshot(),
            firstItem.getSeller() == null ? null : firstItem.getSeller().getFullName());
        String sellerEmail = firstItem == null || firstItem.getSeller() == null ? "-" : firstItem.getSeller().getEmail();

        canvas.text("BILLED TO", MARGIN, canvas.y, BOLD, 8, BLUE);
        canvas.text("SOLD BY", MARGIN + (CONTENT_WIDTH / 2), canvas.y, BOLD, 8, BLUE);
        canvas.y -= 18;
        canvas.text(truncate(buyerName, 38), MARGIN, canvas.y, BOLD, 11, NAVY);
        canvas.text(truncate(sellerName, 38), MARGIN + (CONTENT_WIDTH / 2), canvas.y, BOLD, 11, NAVY);
        canvas.y -= 16;
        canvas.text(truncate(buyerEmail, 42), MARGIN, canvas.y, REGULAR, 9, SLATE);
        canvas.text(truncate(sellerEmail, 42), MARGIN + (CONTENT_WIDTH / 2), canvas.y, REGULAR, 9, SLATE);
        canvas.y -= 30;
    }

    private void drawItems(PdfCanvas canvas, List<OrderItem> items) throws IOException {
        drawItemTableHeader(canvas);
        List<OrderItem> safeItems = items == null ? List.of() : items;
        for (OrderItem item : safeItems) {
            if (canvas.y < 155) {
                canvas.closePage();
                canvas.newPage();
                canvas.text("ORDER ITEMS (CONTINUED)", MARGIN, canvas.y, BOLD, 13, NAVY);
                canvas.y -= 22;
                drawItemTableHeader(canvas);
            }
            drawItemRow(canvas, item);
        }
        canvas.y -= 12;
    }

    private void drawItemTableHeader(PdfCanvas canvas) throws IOException {
        canvas.fillRect(MARGIN, canvas.y - 22, CONTENT_WIDTH, 22, NAVY);
        canvas.text("ITEM", MARGIN + 10, canvas.y - 15, BOLD, 8, WHITE);
        canvas.textRight("QTY", MARGIN + 356, canvas.y - 15, BOLD, 8, WHITE);
        canvas.textRight("UNIT PRICE", MARGIN + 445, canvas.y - 15, BOLD, 8, WHITE);
        canvas.textRight("TOTAL", PAGE_SIZE.getWidth() - MARGIN - 10, canvas.y - 15, BOLD, 8, WHITE);
        canvas.y -= 22;
    }

    private void drawItemRow(PdfCanvas canvas, OrderItem item) throws IOException {
        int quantity = item.getQuantity() == null ? 0 : item.getQuantity();
        BigDecimal unitPrice = amount(item.getPriceAtPurchase());
        BigDecimal total = "AUCTION".equalsIgnoreCase(item.getOrder() == null ? null : item.getOrder().getOrderType())
            ? unitPrice
            : unitPrice.multiply(BigDecimal.valueOf(quantity));
        canvas.fillRect(MARGIN, canvas.y - 31, CONTENT_WIDTH, 31, LIGHT);
        canvas.text(truncate(item.getProductNameSnapshot(), 48), MARGIN + 10, canvas.y - 19, REGULAR, 9, NAVY);
        canvas.textRight(String.valueOf(quantity), MARGIN + 356, canvas.y - 19, REGULAR, 9, NAVY);
        canvas.textRight(money(unitPrice), MARGIN + 445, canvas.y - 19, REGULAR, 9, NAVY);
        canvas.textRight(money(total), PAGE_SIZE.getWidth() - MARGIN - 10, canvas.y - 19, BOLD, 9, NAVY);
        canvas.y -= 34;
    }

    private void drawTotals(PdfCanvas canvas, Order order) throws IOException {
        if (canvas.y < 190) {
            canvas.closePage();
            canvas.newPage();
        }
        float labelX = MARGIN + 300;
        float valueX = PAGE_SIZE.getWidth() - MARGIN;
        canvas.text("Subtotal", labelX, canvas.y, REGULAR, 9, SLATE);
        BigDecimal subtotal = order.getSubtotalAmount() == null ? order.getTotalAmount() : order.getSubtotalAmount();
        canvas.textRight(money(amount(subtotal)), valueX, canvas.y, REGULAR, 9, NAVY);
        canvas.y -= 20;
        canvas.text("Discount", labelX, canvas.y, REGULAR, 9, SLATE);
        canvas.textRight("- " + money(amount(order.getDiscountAmount())), valueX, canvas.y, REGULAR, 9, NAVY);
        canvas.y -= 11;
        canvas.line(labelX, canvas.y, valueX, canvas.y, new Color(192, 202, 216));
        canvas.y -= 23;
        canvas.text("TOTAL", labelX, canvas.y, BOLD, 11, NAVY);
        canvas.textRight(money(amount(order.getTotalAmount())), valueX, canvas.y, BOLD, 14, BLUE);
        canvas.y -= 34;
    }

    private void drawVerification(PdfCanvas canvas, Order order, String verificationUrl) throws IOException, WriterException {
        if (canvas.y < 120) {
            canvas.closePage();
            canvas.newPage();
        }
        float boxHeight = 82;
        canvas.fillRect(MARGIN, canvas.y - boxHeight, CONTENT_WIDTH, boxHeight, new Color(245, 248, 252));
        BufferedImage qrImage = qrCode(verificationUrl, 180);
        PDImageXObject qr = LosslessFactory.createFromImage(canvas.document, qrImage);
        canvas.stream.drawImage(qr, MARGIN + 10, canvas.y - 72, 62, 62);
        canvas.text("VERIFY THIS ORDER", MARGIN + 86, canvas.y - 24, BOLD, 10, NAVY);
        canvas.text("Scan the QR code to view the latest order status.", MARGIN + 86, canvas.y - 42, REGULAR, 9, SLATE);
        canvas.text("Reference: #" + safe(order.getOrderNumber()), MARGIN + 86, canvas.y - 58, REGULAR, 8, BLUE);
        canvas.y -= boxHeight + 18;
        canvas.text("This document is an order summary generated by Mythix. Keep it for your records.", MARGIN, canvas.y, REGULAR, 8, SLATE);
    }

    private BufferedImage qrCode(String value, int size) throws WriterException {
        BitMatrix matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size);
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                image.setRGB(x, y, matrix.get(x, y) ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
            }
        }
        return image;
    }

    private String invoiceFilename(Order order) {
        String reference = safe(order.getOrderNumber()).replaceAll("[^A-Za-z0-9_-]", "-");
        return "mythix-order-" + reference + ".pdf";
    }

    private BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String money(BigDecimal value) {
        return "THB " + amount(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String truncate(String value, int maxLength) {
        String normalized = safe(value);
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength - 3) + "...";
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "-";
    }

    private static final class PdfCanvas {
        private final PDDocument document;
        private PDPage page;
        private PDPageContentStream stream;
        private float y;
        private int pageNumber;

        private PdfCanvas(PDDocument document) {
            this.document = document;
        }

        private void newPage() throws IOException {
            page = new PDPage(PAGE_SIZE);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = PAGE_SIZE.getHeight() - MARGIN;
            pageNumber++;
        }

        private void closePage() throws IOException {
            if (stream == null) return;
            textRight("Page " + pageNumber, PAGE_SIZE.getWidth() - MARGIN, 24, REGULAR, 7, SLATE);
            stream.close();
            stream = null;
        }

        private void fillRect(float x, float y, float width, float height, Color color) throws IOException {
            stream.setNonStrokingColor(color);
            stream.addRect(x, y, width, height);
            stream.fill();
        }

        private void line(float x1, float y1, float x2, float y2, Color color) throws IOException {
            stream.setStrokingColor(color);
            stream.setLineWidth(0.7f);
            stream.moveTo(x1, y1);
            stream.lineTo(x2, y2);
            stream.stroke();
        }

        private void text(String value, float x, float y, PDFont font, float size, Color color) throws IOException {
            String printable = printable(value, font);
            stream.beginText();
            stream.setFont(font, size);
            stream.setNonStrokingColor(color);
            stream.newLineAtOffset(x, y);
            stream.showText(printable);
            stream.endText();
        }

        private void textRight(String value, float rightX, float y, PDFont font, float size, Color color) throws IOException {
            String printable = printable(value, font);
            float width = font.getStringWidth(printable) / 1000f * size;
            text(printable, rightX - width, y, font, size, color);
        }

        private String printable(String value, PDFont font) {
            if (value == null) return "-";
            StringBuilder result = new StringBuilder(value.length());
            value.codePoints().forEach(codePoint -> {
                String character = new String(Character.toChars(codePoint));
                try {
                    font.encode(character);
                    result.append(character);
                } catch (IOException | IllegalArgumentException exception) {
                    result.append('?');
                }
            });
            return result.toString();
        }
    }
}
