package com.mythix.tcg.auth.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.mythix.tcg.user.persistence.UserRepository;
import com.mythix.tcg.seller.persistence.SellerProfileRepository;
import com.mythix.tcg.auth.persistence.PasswordResetTokenRepository;
import com.mythix.tcg.notification.persistence.NotificationEmailRepository;
import com.mythix.tcg.notification.application.SensitiveMailCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SellerProfileRepository sellerProfileRepository;

    @Autowired private PasswordResetTokenRepository resetTokens;
    @Autowired private NotificationEmailRepository emailOutbox;
    @Autowired private SensitiveMailCipher mailCipher;

    @BeforeEach
    void clearUsers() {
        resetTokens.deleteAll();
        emailOutbox.deleteAll();
        sellerProfileRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void registerHashesPasswordAndReturnsBearerToken() throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Naphat\",\"email\":\"Naphat@Example.com\",\"password\":\"password123\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.user.email").value("naphat@example.com"))
            .andExpect(jsonPath("$.user.role").value("USER"))
            .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(userRepository.findByEmail("naphat@example.com")).isPresent();
        assertThat(userRepository.findByEmail("naphat@example.com").orElseThrow().getPassword())
            .isNotEqualTo("password123");
        assertThat(json.path("token").asText()).isNotBlank();
    }

    @Test
    void rejectsDuplicateEmailAndInvalidCredentials() throws Exception {
        String request = "{\"name\":\"Naphat\",\"email\":\"naphat@example.com\",\"password\":\"password123\"}";
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"naphat@example.com\",\"password\":\"wrong-password\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void resetUsesSingleUseEmailTokenAndRevokesPreviousLogin() throws Exception {
        String registered = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Naphat\",\"email\":\"naphat@example.com\",\"password\":\"password123\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String oldJwt = objectMapper.readTree(registered).path("token").asText();

        mockMvc.perform(post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"missing@example.com\"}"))
            .andExpect(status().isNoContent());
        assertThat(emailOutbox.count()).isZero();

        mockMvc.perform(post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"NAPHAT@example.com\"}"))
            .andExpect(status().isNoContent());
        var email = emailOutbox.findAll().get(0);
        assertThat(email.isSensitive()).isTrue();
        assertThat(email.getBody()).doesNotContain("reset-password", "token=");
        String body = mailCipher.decrypt(email.getBody());
        String token = body.split("token=")[1].split("\\n")[0];
        assertThat(resetTokens.findAll().get(0).getTokenHash()).isNotEqualTo(token);

        mockMvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"naphat@example.com\"}"))
            .andExpect(status().isNoContent());
        assertThat(emailOutbox.count()).isEqualTo(1);

        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"newpassword123\"}"))
            .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"another-password\"}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + oldJwt))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"naphat@example.com\",\"password\":\"password123\"}"))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"naphat@example.com\",\"password\":\"newpassword123\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void expiredAndSupersededLinksCannotResetPassword() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Naphat\",\"email\":\"naphat@example.com\",\"password\":\"password123\"}"))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"naphat@example.com\"}")).andExpect(status().isNoContent());
        String first = mailCipher.decrypt(emailOutbox.findAll().get(0).getBody()).split("token=")[1].split("\\n")[0];
        var record = resetTokens.findAll().get(0);
        record.setCreatedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(31));
        record.setExpiresAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
        resetTokens.saveAndFlush(record);
        mockMvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + first + "\",\"newPassword\":\"newpassword123\"}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"naphat@example.com\"}")).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
            .content("{\"token\":\"" + first + "\",\"newPassword\":\"newpassword123\"}"))
            .andExpect(status().isBadRequest());
        assertThat(emailOutbox.count()).isEqualTo(2);
    }

    @Test
    void loginAndMeRequireAndAcceptBearerToken() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Naphat\",\"email\":\"naphat@example.com\",\"password\":\"password123\"}"))
            .andExpect(status().isCreated());

        var savedUser = userRepository.findByEmail("naphat@example.com").orElseThrow();
        savedUser.setAvatarUrl("https://example.com/avatar.png");
        userRepository.saveAndFlush(savedUser);

        String loginResponse = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"naphat@example.com\",\"password\":\"password123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.avatarUrl").value("https://example.com/avatar.png"))
            .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(loginResponse).path("token").asText();

        mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("naphat@example.com"))
            .andExpect(jsonPath("$.avatarUrl").value("https://example.com/avatar.png"))
            .andExpect(jsonPath("$.sellerStatus").value("NONE"));
    }

    @Test
    void forgedMockBearerTokenCannotAuthenticate() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Buyer\",\"email\":\"buyer@mythix.com\",\"password\":\"password123\"}"))
            .andExpect(status().isCreated());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer mock-token-buyer"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void avatarRemovalRequiresAuthenticationAndPersistsOnlyForCaller() throws Exception {
        String token = registerAndGetToken();
        var user = userRepository.findByEmail("naphat@example.com").orElseThrow();
        user.setAvatarUrl("https://example.com/shared.png");
        userRepository.saveAndFlush(user);
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Other\",\"email\":\"other@example.com\",\"password\":\"password123\"}"))
            .andExpect(status().isCreated());
        var other = userRepository.findByEmail("other@example.com").orElseThrow();
        other.setAvatarUrl("https://example.com/shared.png");
        userRepository.saveAndFlush(other);

        mockMvc.perform(delete("/api/users/profile/avatar")).andExpect(status().isUnauthorized());
        assertThat(userRepository.findById(user.getId()).orElseThrow().getAvatarUrl()).isNotNull();
        String response = mockMvc.perform(delete("/api/users/profile/avatar").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(response).has("avatarUrl")).isTrue();
        assertThat(objectMapper.readTree(response).get("avatarUrl").isNull()).isTrue();
        assertThat(userRepository.findById(user.getId()).orElseThrow().getAvatarUrl()).isNull();
        assertThat(userRepository.findById(other.getId()).orElseThrow().getAvatarUrl()).isEqualTo("https://example.com/shared.png");
        mockMvc.perform(delete("/api/users/profile/avatar").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk());
    }

    @Test
    void authenticatedUserCanReadAndUpdateTheirContactProfile() throws Exception {
        String token = registerAndGetToken();

        mockMvc.perform(get("/api/users/profile").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("naphat@example.com"))
            .andExpect(jsonPath("$.phone").doesNotExist());

        mockMvc.perform(put("/api/users/profile").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\" Naphat P. \",\"phone\":\" 081-234-5678 \",\"line\":\" naphat \",\"facebook\":\" fb.com/naphat \"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Naphat P."))
            .andExpect(jsonPath("$.phone").value("081-234-5678"))
            .andExpect(jsonPath("$.line").value("naphat"))
            .andExpect(jsonPath("$.facebook").value("fb.com/naphat"));

        mockMvc.perform(get("/api/users/profile"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUserCanBecomeSellerOnlyOnce() throws Exception {
        String token = registerAndGetToken();
        String request = "{\"shopName\":\" Mythix Vault \",\"shopDescription\":\"Trusted cards\",\"phone\":\"0812345678\",\"line\":\"vault\"}";

        mockMvc.perform(post("/api/sellers/verify").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.shopName").value("Mythix Vault"))
            .andExpect(jsonPath("$.sellerStatus").value("PENDING"))
            .andExpect(jsonPath("$.verifiedAt").isEmpty());

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("USER"))
            .andExpect(jsonPath("$.sellerStatus").value("PENDING"));

        mockMvc.perform(post("/api/sellers/verify").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SELLER_ALREADY_APPROVED"));
    }

    @Test
    void sellerCanUpdateStoreWhileGuestsOnlySeePublicFields() throws Exception {
        String token = registerAndGetToken();
        mockMvc.perform(post("/api/sellers/verify").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"shopName\":\"Original\"}"))
            .andExpect(status().isCreated());
        mockMvc.perform(put("/api/sellers/me/store").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"shopName\":\"Mythix Vault\",\"shopDescription\":\"Cards\",\"line\":\"vault\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.shopName").value("Mythix Vault"))
            .andExpect(jsonPath("$.line").value("vault"));
        Long userId = userRepository.findByEmail("naphat@example.com").orElseThrow().getId();
        mockMvc.perform(get("/api/stores/{sellerId}", userId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.shopName").value("Mythix Vault"))
            .andExpect(jsonPath("$.line").isEmpty())
            .andExpect(jsonPath("$.phone").isEmpty())
            .andExpect(jsonPath("$.facebook").isEmpty())
            .andExpect(jsonPath("$.otherContact").isEmpty());
    }

    @Test
    void profileSupportsEmailAndAvatarUrl() throws Exception {
        String token = registerAndGetToken();
        mockMvc.perform(put("/api/users/profile").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"new@example.com\",\"avatarUrl\":\"https://example.com/me.png\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("new@example.com"))
            .andExpect(jsonPath("$.avatarUrl").value("https://example.com/me.png"));
    }

    @Test
    void partialContactUpdatePreservesProfileFieldsNotIncludedInRequest() throws Exception {
        String token = registerAndGetToken();
        mockMvc.perform(put("/api/users/profile").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Naphat P.\",\"email\":\"profile@example.com\",\"avatarUrl\":\"https://example.com/avatar.png\"}"))
            .andExpect(status().isOk());

        mockMvc.perform(put("/api/users/profile").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"081-234-5678\",\"line\":\"naphat\",\"facebook\":\"fb.com/naphat\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Naphat P."))
            .andExpect(jsonPath("$.email").value("profile@example.com"))
            .andExpect(jsonPath("$.avatarUrl").value("https://example.com/avatar.png"))
            .andExpect(jsonPath("$.phone").value("081-234-5678"))
            .andExpect(jsonPath("$.line").value("naphat"))
            .andExpect(jsonPath("$.facebook").value("fb.com/naphat"));
    }

    @Test
    void sellerVerificationValidatesRequiredShopName() throws Exception {
        mockMvc.perform(post("/api/sellers/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"shopName\":\"\"}"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/sellers/verify").header("Authorization", "Bearer " + registerAndGetToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"shopName\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private String registerAndGetToken() throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Naphat\",\"email\":\"naphat@example.com\",\"password\":\"password123\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("token").asText();
    }
}
