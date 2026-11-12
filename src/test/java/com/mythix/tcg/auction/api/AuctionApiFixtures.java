package com.mythix.tcg.auction.api;

import com.mythix.tcg.auth.security.JwtService;
import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

final class AuctionApiFixtures {
    static final String AUCTIONS = "/api/seller/auctions";
    static final String ADMIN_REQUESTS = "/api/admin/auction-cancellation-requests";
    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jGZkAAAAASUVORK5CYII=");

    private final JwtService jwtService;
    private final UserRepository users;
    private final CardRepository cards;
    private final ProductRepository products;

    AuctionApiFixtures(JwtService jwtService, UserRepository users, CardRepository cards, ProductRepository products) {
        this.jwtService = jwtService;
        this.users = users;
        this.cards = cards;
        this.products = products;
    }

    User user(String role, String sellerStatus) {
        return users.save(User.builder()
            .email("auction-" + UUID.randomUUID() + "@example.com")
            .password("hash").fullName("Auction test user").role(role).sellerStatus(sellerStatus).build());
    }

    Product product(User seller, int quantity, String status) {
        return product(seller, quantity, status, "Near Mint");
    }

    Product product(User seller, int quantity, String status, String condition) {
        Card card = cards.save(Card.builder()
            .id("auction-" + UUID.randomUUID()).name("Empoleon").supertype("Pokemon")
            .setName("Diamond & Pearl").cardNumber("4").imageSmall("https://example.test/em-small.png")
            .imageLarge("https://example.test/em-large.png").build());
        return products.save(Product.builder()
            .seller(seller).card(card).condition(condition).language("EN").price(new BigDecimal("10.00"))
            .onHand(quantity).status(status).build());
    }

    String bearer(User user) {
        return "Bearer " + jwtService.generateToken(user);
    }

    static MockMultipartHttpServletRequestBuilder draft() {
        return multipart(AUCTIONS + "/drafts");
    }

    static MockMultipartHttpServletRequestBuilder edit(long auctionId) {
        return multipart(HttpMethod.PUT, AUCTIONS + "/{id}/draft", auctionId);
    }

    static MockMultipartHttpServletRequestBuilder setup(
        MockMultipartHttpServletRequestBuilder request, long productId, String price, String startMode, String durationHours
    ) {
        request.param("productId", Long.toString(productId))
            .param("condition", "NM").param("startPrice", price)
            .param("startMode", startMode).param("durationHours", durationHours);
        return request;
    }

    static MockMultipartHttpServletRequestBuilder photos(MockMultipartHttpServletRequestBuilder request) {
        return request.file(image("frontImage")).file(image("backImage"));
    }

    static MockMultipartFile image(String part) {
        return new MockMultipartFile(part, part + ".png", "image/png", PNG);
    }
}
