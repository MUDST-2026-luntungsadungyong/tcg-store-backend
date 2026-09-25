package com.mythix.tcg.admin.api;
import com.mythix.tcg.order.persistence.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/admin/orders") @RequiredArgsConstructor
public class AdminOrderLogsController {
 private final OrderRepository orderRepository;
 @GetMapping public Page<AdminOrderLogResponse> orders(@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
  var pageable=PageRequest.of(Math.max(page,0),Math.min(Math.max(size,1),100));
  return (status==null||status.isBlank()?orderRepository.findAllByOrderByCreatedAtDesc(pageable):orderRepository.findAllByStatusOrderByCreatedAtDesc(status,pageable)).map(AdminOrderLogResponse::from);
 }
}
