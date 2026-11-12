package com.mythix.tcg.voucher.persistence;

import com.mythix.tcg.voucher.domain.UserVoucher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserVoucherRepository extends JpaRepository<UserVoucher, Long> {

    List<UserVoucher> findAllByUserId(Long userId);

    List<UserVoucher> findAllByUserIdOrderByClaimedAtDesc(Long userId);

    List<UserVoucher> findAllByUserIdAndStatusOrderByClaimedAtDesc(Long userId, String status);

    Optional<UserVoucher> findByUserIdAndVoucherId(Long userId, Long voucherId);

    @Query("SELECT uv FROM UserVoucher uv WHERE uv.user.id = :userId AND UPPER(uv.voucher.code) = UPPER(:code)")
    Optional<UserVoucher> findByUserIdAndVoucherCodeIgnoreCase(@Param("userId") Long userId, @Param("code") String code);

    boolean existsByUserIdAndVoucherId(Long userId, Long voucherId);

    long countByUserIdAndVoucherId(Long userId, Long voucherId);

    long countByVoucherId(Long voucherId);

    @Query("SELECT uv.voucher.id, COUNT(uv) FROM UserVoucher uv GROUP BY uv.voucher.id")
    List<Object[]> countGroupedByVoucherId();

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM UserVoucher uv WHERE uv.voucher.id = :voucherId")
    int deleteAllByVoucherId(@Param("voucherId") Long voucherId);
}
