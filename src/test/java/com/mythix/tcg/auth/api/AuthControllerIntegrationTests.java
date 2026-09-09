package com.mythix.tcg.auth.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mythix.tcg.user.persistence.UserRepository;
import com.mythix.tcg.seller.persistence.SellerProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @BeforeEach
    void clearUsers() {
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
    void loginAndMeRequireAndAcceptBearerToken() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Naphat\",\"email\":\"naphat@example.com\",\"password\":\"password123\"}"))
            .andExpect(status().isCreated());

        String loginResponse = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"naphat@example.com\",\"password\":\"password123\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(loginResponse).path("token").asText();

        mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("naphat@example.com"))
            .andExpect(jsonPath("$.sellerStatus").value("NONE"));
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
            .andExpect(jsonPath("$.sellerStatus").value("VERIFIED"))
            .andExpect(jsonPath("$.verifiedAt").isNotEmpty());

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("SELLER"))
            .andExpect(jsonPath("$.sellerStatus").value("VERIFIED"));

        mockMvc.perform(post("/api/sellers/verify").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SELLER_ALREADY_VERIFIED"));
    }

    @Test
    void sellerCanUpdateStoreAndGuestsCanReadIt() throws Exception {
        String token = registerAndGetToken();
        mockMvc.perform(post("/api/sellers/verify").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"shopName\":\"Original\"}"))
            .andExpect(status().isCreated());
        mockMvc.perform(put("/api/sellers/me/store").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"shopName\":\"Mythix Vault\",\"shopDescription\":\"Cards\",\"line\":\"vault\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.shopName").value("Mythix Vault"));
        Long userId = userRepository.findByEmail("naphat@example.com").orElseThrow().getId();
        mockMvc.perform(get("/api/stores/{sellerId}", userId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.line").value("vault"));
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
