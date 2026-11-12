package com.mythix.tcg.seller.persistence;

import com.mythix.tcg.seller.domain.SellerStockAlertState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerStockAlertStateRepository extends JpaRepository<SellerStockAlertState, Long> {
    java.util.List<SellerStockAlertState> findAllByProduct_Seller_Id(Long sellerId);

    @org.springframework.data.jpa.repository.Query("select distinct s.product.seller.id from SellerStockAlertState s")
    java.util.List<Long> findDistinctSellerIdsWithState();
}
