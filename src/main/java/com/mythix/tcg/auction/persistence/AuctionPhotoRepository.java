package com.mythix.tcg.auction.persistence;

import com.mythix.tcg.auction.domain.AuctionPhoto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuctionPhotoRepository extends JpaRepository<AuctionPhoto, AuctionPhoto.Id> {
    @Query("select p.id from AuctionPhoto p where p.id.auctionId in :auctionIds")
    List<AuctionPhoto.Id> findPhotoIds(@Param("auctionIds") List<Long> auctionIds);
}
