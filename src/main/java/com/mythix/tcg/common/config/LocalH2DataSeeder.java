package com.mythix.tcg.common.config;

import com.mythix.tcg.catalog.domain.Card;
import com.mythix.tcg.catalog.persistence.CardRepository;
import com.mythix.tcg.order.domain.Order;
import com.mythix.tcg.order.domain.OrderItem;
import com.mythix.tcg.order.persistence.OrderRepository;
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
    private final OrderRepository orderRepository;
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

        Card blastoise = cardRepository.save(Card.builder()
            .id("base1-2")
            .name("Blastoise")
            .supertype("Pokémon")
            .setName("Base Set")
            .cardNumber("2/102")
            .imageSmall("https://images.pokemontcg.io/base1/2.png")
            .imageLarge("https://images.pokemontcg.io/base1/2_hires.png")
            .marketPrice(BigDecimal.valueOf(1100.00))
            .active(true)
            .build());

        Card venusaur = cardRepository.save(Card.builder()
            .id("base1-15")
            .name("Venusaur")
            .supertype("Pokémon")
            .setName("Base Set")
            .cardNumber("15/102")
            .imageSmall("https://images.pokemontcg.io/base1/15.png")
            .imageLarge("https://images.pokemontcg.io/base1/15_hires.png")
            .marketPrice(BigDecimal.valueOf(980.00))
            .active(true)
            .build());

        // 4. Products / Listings
        Product charizardListing = productRepository.save(Product.builder()
            .seller(seller)
            .card(charizard)
            .customImageUrl("https://images.pokemontcg.io/base1/4_hires.png")
            .condition("NM")
            .language("EN")
            .price(BigDecimal.valueOf(1200.00))
            .cost(BigDecimal.valueOf(850.00))
            .onHand(5)
            .reserved(1)
            .status("SHOW")
            .build());

        Product pikachuListing = productRepository.save(Product.builder()
            .seller(seller)
            .card(pikachu)
            .customImageUrl("https://images.pokemontcg.io/base1/58_hires.png")
            .condition("LP")
            .language("EN")
            .price(BigDecimal.valueOf(400.00))
            .cost(BigDecimal.valueOf(220.00))
            .onHand(10)
            .reserved(0)
            .status("SHOW")
            .build());

        Product mewtwoListing = productRepository.save(Product.builder()
            .seller(seller)
            .card(mewtwo)
            .customImageUrl("https://images.pokemontcg.io/base1/10_hires.png")
            .condition("NM")
            .language("EN")
            .price(BigDecimal.valueOf(850.00))
            .cost(BigDecimal.valueOf(600.00))
            .onHand(3)
            .reserved(0)
            .status("SHOW")
            .build());

        Product blastoiseListing = productRepository.save(Product.builder()
            .seller(seller)
            .card(blastoise)
            .customImageUrl("https://images.pokemontcg.io/base1/2_hires.png")
            .condition("NM")
            .language("EN")
            .price(BigDecimal.valueOf(1050.00))
            .cost(BigDecimal.valueOf(720.00))
            .onHand(0)
            .reserved(0)
            .status("SHOW")
            .build());

        Product venusaurListing = productRepository.save(Product.builder()
            .seller(seller)
            .card(venusaur)
            .customImageUrl("https://images.pokemontcg.io/base1/15_hires.png")
            .condition("NM")
            .language("EN")
            .price(BigDecimal.valueOf(950.00))
            .cost(BigDecimal.valueOf(650.00))
            .onHand(3)
            .reserved(0)
            .status("SHOW")
            .build());

        // 4.5 Sample Orders for Stock Forecast testing
        Order fulfilledOrder = Order.builder()
            .orderNumber("ORD-DEMO-1001")
            .buyer(buyer)
            .status("FULFILLED")
            .orderType("DIRECT")
            .subtotalAmount(BigDecimal.valueOf(10250.00))
            .totalAmount(BigDecimal.valueOf(10250.00))
            .build();
        fulfilledOrder.getItems().add(OrderItem.builder()
            .order(fulfilledOrder)
            .product(charizardListing)
            .seller(seller)
            .cardId(charizard.getId())
            .productNameSnapshot(charizard.getName())
            .shopNameSnapshot("Pewter City Card Vault")
            .priceAtPurchase(charizardListing.getPrice())
            .costAtPurchase(charizardListing.getCost())
            .quantity(5)
            .build());
        fulfilledOrder.getItems().add(OrderItem.builder()
            .order(fulfilledOrder)
            .product(blastoiseListing)
            .seller(seller)
            .cardId(blastoise.getId())
            .productNameSnapshot(blastoise.getName())
            .shopNameSnapshot("Pewter City Card Vault")
            .priceAtPurchase(blastoiseListing.getPrice())
            .costAtPurchase(blastoiseListing.getCost())
            .quantity(4)
            .build());
        orderRepository.save(fulfilledOrder);

        Order activeOrder = Order.builder()
            .orderNumber("ORD-DEMO-1002")
            .buyer(buyer)
            .status("CONFIRMED")
            .orderType("DIRECT")
            .subtotalAmount(BigDecimal.valueOf(4850.00))
            .totalAmount(BigDecimal.valueOf(4850.00))
            .build();
        activeOrder.getItems().add(OrderItem.builder()
            .order(activeOrder)
            .product(charizardListing)
            .seller(seller)
            .cardId(charizard.getId())
            .productNameSnapshot(charizard.getName())
            .shopNameSnapshot("Pewter City Card Vault")
            .priceAtPurchase(charizardListing.getPrice())
            .costAtPurchase(charizardListing.getCost())
            .quantity(1)
            .build());
        activeOrder.getItems().add(OrderItem.builder()
            .order(activeOrder)
            .product(pikachuListing)
            .seller(seller)
            .cardId(pikachu.getId())
            .productNameSnapshot(pikachu.getName())
            .shopNameSnapshot("Pewter City Card Vault")
            .priceAtPurchase(pikachuListing.getPrice())
            .costAtPurchase(pikachuListing.getCost())
            .quantity(2)
            .build());
        activeOrder.getItems().add(OrderItem.builder()
            .order(activeOrder)
            .product(venusaurListing)
            .seller(seller)
            .cardId(venusaur.getId())
            .productNameSnapshot(venusaur.getName())
            .shopNameSnapshot("Pewter City Card Vault")
            .priceAtPurchase(venusaurListing.getPrice())
            .costAtPurchase(venusaurListing.getCost())
            .quantity(3)
            .build());
        orderRepository.save(activeOrder);

        Order cancelledOrder = Order.builder()
            .orderNumber("ORD-DEMO-1003")
            .buyer(buyer)
            .status("CANCELLED")
            .orderType("DIRECT")
            .subtotalAmount(BigDecimal.valueOf(4250.00))
            .totalAmount(BigDecimal.valueOf(4250.00))
            .build();
        cancelledOrder.getItems().add(OrderItem.builder()
            .order(cancelledOrder)
            .product(mewtwoListing)
            .seller(seller)
            .cardId(mewtwo.getId())
            .productNameSnapshot(mewtwo.getName())
            .shopNameSnapshot("Pewter City Card Vault")
            .priceAtPurchase(mewtwoListing.getPrice())
            .costAtPurchase(mewtwoListing.getCost())
            .quantity(5)
            .build());
        orderRepository.save(cancelledOrder);

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
