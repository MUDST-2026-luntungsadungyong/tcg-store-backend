package com.mythix.tcg.common.config;

import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.product.domain.Product;
import com.mythix.tcg.product.persistence.ProductRepository;
import com.mythix.tcg.seller.domain.SellerProfile;
import com.mythix.tcg.seller.persistence.SellerProfileRepository;
import com.mythix.tcg.user.domain.User;
import com.mythix.tcg.user.persistence.UserRepository;
import com.mythix.tcg.voucher.domain.Voucher;
import com.mythix.tcg.voucher.persistence.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Slf4j
@Component
@Profile("local-h2")
@RequiredArgsConstructor
public class LocalH2DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final SellerProfileRepository sellerProfileRepository;
    private final CardRepository cardRepository;
    private final ProductRepository productRepository;
    private final VoucherRepository voucherRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        log.info("==> [Local H2] Seeding initial test data for in-memory database...");

        String encodedPw = passwordEncoder.encode("password123");

        // 1. Users
        User admin = userRepository.save(User.builder()
            .email("admin@mythix.com")
            .password(encodedPw)
            .fullName("Platform Administrator")
            .role("ADMIN")
            .sellerStatus("NONE")
            .build());

        User seller = userRepository.save(User.builder()
            .email("seller@mythix.com")
            .password(encodedPw)
            .fullName("Brock Pewter")
            .role("USER")
            .sellerStatus("APPROVED")
            .build());

        User buyer = userRepository.save(User.builder()
            .email("buyer@mythix.com")
            .password(encodedPw)
            .fullName("Ash Ketchum")
            .role("USER")
            .sellerStatus("NONE")
            .build());

        // 2. Seller Profile
        sellerProfileRepository.save(SellerProfile.builder()
            .user(seller)
            .shopName("Pewter City Card Vault")
            .shopDescription("Original rock-solid TCG cards")
            .build());

        // 3. Global Cards
        Card charizard = cardRepository.save(Card.builder()
            .id("base1-4")
            .name("Charizard")
            .supertype("Pokémon")
            .setName("Base Set")
            .cardNumber("4/102")
            .imageSmall("https://images.pokemontcg.io/base1/4.png")
            .imageLarge("https://images.pokemontcg.io/base1/4_hires.png")
            .marketPrice(BigDecimal.valueOf(1500.00))
            .active(true)
            .build());

        Card pikachu = cardRepository.save(Card.builder()
            .id("base1-58")
            .name("Pikachu")
            .supertype("Pokémon")
            .setName("Base Set")
            .cardNumber("58/102")
            .imageSmall("https://images.pokemontcg.io/base1/58.png")
            .imageLarge("https://images.pokemontcg.io/base1/58_hires.png")
            .marketPrice(BigDecimal.valueOf(450.00))
            .active(true)
            .build());

        Card mewtwo = cardRepository.save(Card.builder()
            .id("base1-10")
            .name("Mewtwo")
            .supertype("Pokémon")
            .setName("Base Set")
            .cardNumber("10/102")
            .imageSmall("https://images.pokemontcg.io/base1/10.png")
            .imageLarge("https://images.pokemontcg.io/base1/10_hires.png")
            .marketPrice(BigDecimal.valueOf(900.00))
            .active(true)
            .build());

        // 4. Products / Listings
        productRepository.save(Product.builder()
            .seller(seller)
            .card(charizard)
            .customImageUrl("https://images.pokemontcg.io/base1/4_hires.png")
            .condition("NM")
            .language("EN")
            .price(BigDecimal.valueOf(1200.00))
            .onHand(5)
            .reserved(0)
            .status("SHOW")
            .build());

        productRepository.save(Product.builder()
            .seller(seller)
            .card(pikachu)
            .customImageUrl("https://images.pokemontcg.io/base1/58_hires.png")
            .condition("LP")
            .language("EN")
            .price(BigDecimal.valueOf(400.00))
            .onHand(10)
            .reserved(0)
            .status("SHOW")
            .build());

        productRepository.save(Product.builder()
            .seller(seller)
            .card(mewtwo)
            .customImageUrl("https://images.pokemontcg.io/base1/10_hires.png")
            .condition("NM")
            .language("EN")
            .price(BigDecimal.valueOf(850.00))
            .onHand(3)
            .reserved(0)
            .status("SHOW")
            .build());

        // 5. Vouchers
        voucherRepository.save(Voucher.builder()
            .code("WELCOME10")
            .title("ส่วนลด 10% สมาชิกใหม่")
            .description("ลด 10% สูงสุด 100 บาท เมื่อซื้อขั้นต่ำ 300 บาท")
            .discountType("PERCENTAGE")
            .discountValue(BigDecimal.valueOf(10.00))
            .minSpend(BigDecimal.valueOf(300.00))
            .maxDiscountAmount(BigDecimal.valueOf(100.00))
            .totalQuota(500)
            .usedQuota(45)
            .perUserLimit(1)
            .startTime(LocalDateTime.now().minusDays(1))
            .endTime(LocalDateTime.now().plusDays(90))
            .isActive(true)
            .build());

        voucherRepository.save(Voucher.builder()
            .code("MYTHIX50")
            .title("ส่วนลดพิเศษ ฿50")
            .description("ลดทันที 50 บาท เมื่อช้อปการ์ดครบ 500 บาท")
            .discountType("FIXED_AMOUNT")
            .discountValue(BigDecimal.valueOf(50.00))
            .minSpend(BigDecimal.valueOf(500.00))
            .maxDiscountAmount(null)
            .totalQuota(300)
            .usedQuota(120)
            .perUserLimit(1)
            .startTime(LocalDateTime.now().minusDays(1))
            .endTime(LocalDateTime.now().plusDays(60))
            .isActive(true)
            .build());

        voucherRepository.save(Voucher.builder()
            .code("FLASH100")
            .title("Flash Sale ฿100")
            .description("ลด 100 บาท สำหรับยอดสั่งซื้อตั้งแต่ 1,000 บาทขึ้นไป")
            .discountType("FIXED_AMOUNT")
            .discountValue(BigDecimal.valueOf(100.00))
            .minSpend(BigDecimal.valueOf(1000.00))
            .maxDiscountAmount(null)
            .totalQuota(100)
            .usedQuota(85)
            .perUserLimit(1)
            .startTime(LocalDateTime.now().minusDays(1))
            .endTime(LocalDateTime.now().plusDays(30))
            .isActive(true)
            .build());

        voucherRepository.save(Voucher.builder()
            .code("POKEMON15")
            .title("Mega Deal Pokemon 15%")
            .description("ลด 15% สูงสุด 200 บาท เมื่อซื้อขั้นต่ำ 800 บาท")
            .discountType("PERCENTAGE")
            .discountValue(BigDecimal.valueOf(15.00))
            .minSpend(BigDecimal.valueOf(800.00))
            .maxDiscountAmount(BigDecimal.valueOf(200.00))
            .totalQuota(200)
            .usedQuota(30)
            .perUserLimit(1)
            .startTime(LocalDateTime.now().minusDays(1))
            .endTime(LocalDateTime.now().plusDays(45))
            .isActive(true)
            .build());

        log.info("==> [Local H2] Seeding completed successfully!");
    }
}
