package com.mythix.tcg.admin.api;

import com.mythix.tcg.admin.domain.AuditLog;
import com.mythix.tcg.auth.security.JwtService;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.voucher.domain.UserVoucher;
import com.mythix.tcg.voucher.domain.Voucher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminVoucherControllerIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @PersistenceContext private EntityManager entityManager;

    private User admin;
    private User buyer;

    @BeforeEach
    void setUp() {
        admin = persistUser("admin-voucher@example.com", "ADMIN");
        buyer = persistUser("buyer-voucher@example.com", "USER");
        entityManager.flush();
    }

    @Test
    void rejectsUnauthenticatedAndNonAdminRequests() throws Exception {
        mockMvc.perform(get("/api/admin/vouchers"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/vouchers").header("Authorization", bearer(buyer)))
            .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/vouchers")
                .header("Authorization", bearer(buyer))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void adminCanCreateUpdateToggleAndSafelyDeleteVoucherPreservingOrderSnapshot() throws Exception {
        String createBody = """
            {
              "code": "live50",
              "title": "Live Test Discount",
              "description": "Save 50 THB on 300+",
              "discountType": "FIXED_AMOUNT",
              "discountValue": 50.00,
              "minSpend": 300.00,
              "totalQuota": 100,
              "startTime": "2026-01-01T00:00:00Z",
              "endTime": "2029-12-31T23:59:59Z",
              "isActive": true
            }
            """;

        mockMvc.perform(post("/api/admin/vouchers")
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value("LIVE50"))
            .andExpect(jsonPath("$.effectiveStatus").value("ACTIVE"))
            .andExpect(jsonPath("$.remainingQuota").value(100));

        // Duplicate code returns 409 CONFLICT
        mockMvc.perform(post("/api/admin/vouchers")
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VOUCHER_CODE_EXISTS"));

        Voucher created = entityManager.createQuery("SELECT v FROM Voucher v WHERE v.code = 'LIVE50'", Voucher.class)
            .getSingleResult();

        // Buyer sees the newly created voucher immediately in public list and can claim it
        mockMvc.perform(get("/api/vouchers").header("Authorization", bearer(buyer)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.code=='LIVE50')].title").value("Live Test Discount"));

        mockMvc.perform(post("/api/vouchers/{id}/claim", created.getId())
                .header("Authorization", bearer(buyer)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.isActive").value(true))
            .andExpect(jsonPath("$.startTime").isNotEmpty());

        // Simulate voucher used in an order
        created.setUsedQuota(2);
        Order order = Order.builder()
            .orderNumber("ORD-VOUCHER-TEST-1")
            .buyer(buyer)
            .status("PAID")
            .subtotalAmount(BigDecimal.valueOf(500.00))
            .discountAmount(BigDecimal.valueOf(50.00))
            .voucher(created)
            .voucherCodeSnapshot("LIVE50")
            .totalAmount(BigDecimal.valueOf(450.00))
            .platformFee(BigDecimal.ZERO)
            .sellerNetAmount(BigDecimal.valueOf(500.00))
            .build();
        entityManager.persist(order);
        entityManager.flush();

        // Cannot lower totalQuota below usedQuota (2)
        String invalidQuotaBody = """
            {
              "code": "LIVE50",
              "title": "Live Test Discount",
              "description": "Updated",
              "discountType": "FIXED_AMOUNT",
              "discountValue": 50.00,
              "minSpend": 300.00,
              "totalQuota": 1,
              "startTime": "2026-01-01T00:00:00Z",
              "endTime": "2029-12-31T23:59:59Z",
              "isActive": true
            }
            """;
        mockMvc.perform(put("/api/admin/vouchers/{id}", created.getId())
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidQuotaBody))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VOUCHER_VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors.totalQuota").exists());

        // Admin disables voucher -> immediately hidden from public center & reflected in buyer's wallet
        mockMvc.perform(patch("/api/admin/vouchers/{id}/status", created.getId())
                .header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"isActive\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.isActive").value(false))
            .andExpect(jsonPath("$.effectiveStatus").value("INACTIVE"))
            .andExpect(jsonPath("$.claimedCount").value(1))
            .andExpect(jsonPath("$.usedOrdersCount").value(1));

        mockMvc.perform(get("/api/vouchers").header("Authorization", bearer(buyer)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.code=='LIVE50')]").isEmpty());

        mockMvc.perform(get("/api/vouchers/my-vouchers").header("Authorization", bearer(buyer)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.code=='LIVE50')].isActive").value(false));

        // Admin safely deletes voucher -> detaches order, deletes wallet claim, keeps order snapshot intact
        mockMvc.perform(delete("/api/admin/vouchers/{id}", created.getId())
                .header("Authorization", bearer(admin)))
            .andExpect(status().isNoContent());

        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(Voucher.class, created.getId())).isNull();
        List<UserVoucher> remainingClaims = entityManager
            .createQuery("SELECT uv FROM UserVoucher uv WHERE uv.voucher.id = :vid", UserVoucher.class)
            .setParameter("vid", created.getId())
            .getResultList();
        assertThat(remainingClaims).isEmpty();

        Order persistedOrder = entityManager.find(Order.class, order.getId());
        assertThat(persistedOrder).isNotNull();
        assertThat(persistedOrder.getVoucher()).isNull();
        assertThat(persistedOrder.getVoucherCodeSnapshot()).isEqualTo("LIVE50");
        assertThat(persistedOrder.getDiscountAmount()).isEqualByComparingTo("50.00");

        List<AuditLog> voucherLogs = entityManager
            .createQuery("SELECT a FROM AuditLog a WHERE a.targetType = 'VOUCHER' ORDER BY a.id ASC", AuditLog.class)
            .getResultList();
        assertThat(voucherLogs).extracting(AuditLog::getAction)
            .containsExactly("CREATE_VOUCHER", "TOGGLE_VOUCHER", "DELETE_VOUCHER");
    }

    private User persistUser(String email, String role) {
        User user = User.builder()
            .email(email)
            .password("hash")
            .fullName(email)
            .role(role)
            .sellerStatus("NONE")
            .build();
        entityManager.persist(user);
        return user;
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateToken(user);
    }
}
