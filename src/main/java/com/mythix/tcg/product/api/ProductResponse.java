package com.mythix.tcg.product.api;

import com.mythix.tcg.product.domain.Product;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductResponse {

    private String id;
    private String cardId;
    private String sellerId;
    private String sellerName;
    private String name;
    private String game;
    private String image;
    private BigDecimal price;
    private BigDecimal cost;
    private Integer stock;
    private String status;
    private String condition;
    private String language;
    private String set;
    private String rarity;
    private String stage;
    private String cardNumber;
    private BigDecimal marketPrice;
    private String artist;
    private String hp;
    private String types;

    public static ProductResponse fromEntity(Product product) {
        return fromEntity(product, null);
    }

    public static ProductResponse fromEntity(Product product, String sellerName) {
        if (product == null) {
            return null;
        }

        String image = product.getCustomImageUrl();
        if (image == null || image.isBlank()) {
            if (product.getCard() != null) {
                image = product.getCard().getImageLarge() != null && !product.getCard().getImageLarge().isBlank()
                        ? product.getCard().getImageLarge()
                        : product.getCard().getImageSmall();
            }
        }

        int onHand = product.getOnHand() != null ? product.getOnHand() : 0;
        int reserved = product.getReserved() != null ? product.getReserved() : 0;
        int availableStock = Math.max(0, onHand - reserved);

        return ProductResponse.builder()
                .id(product.getId() != null ? String.valueOf(product.getId()) : null)
                .cardId(product.getCard() != null ? product.getCard().getId() : null)
                .sellerId(product.getSeller() != null && product.getSeller().getId() != null
                        ? String.valueOf(product.getSeller().getId()) : null)
                .sellerName(sellerName)
                .name(product.getCard() != null ? product.getCard().getName() : null)
                .game("Pokemon")
                .image(image)
                .price(product.getPrice())
                .cost(product.getCost())
                .stock(availableStock)
                .status(product.getStatus())
                .condition(product.getCondition())
                .language(product.getLanguage())
                .set(product.getCard() != null ? product.getCard().getSetName() : null)
                .rarity(product.getCard() != null ? product.getCard().getRarity() : null)
                .stage(product.getCard() != null ? product.getCard().getSubtypes() : null)
                .cardNumber(product.getCard() != null ? product.getCard().getCardNumber() : null)
                .marketPrice(product.getCard() != null ? product.getCard().getMarketPrice() : null)
                .artist(product.getCard() != null ? product.getCard().getArtist() : null)
                .hp(product.getCard() != null ? product.getCard().getHp() : null)
                .types(product.getCard() != null ? product.getCard().getTypes() : null)
                .build();
    }
}
