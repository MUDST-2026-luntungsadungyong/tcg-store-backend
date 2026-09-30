package com.mythix.tcg.admin.api;
import com.mythix.tcg.order.persistence.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.time.LocalDate;
@RestController @RequestMapping("/api/admin/orders") @RequiredArgsConstructor
public class AdminOrderLogsController {
 private final OrderRepository orderRepository;
 @GetMapping public Page<AdminOrderLogResponse> orders(@RequestParam(required=false) String query,@RequestParam(defaultValue="ORDER_NUMBER") String searchBy,@RequestParam(required=false) List<String> statuses,@RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate fromDate,@RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate toDate,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
  var pageable=PageRequest.of(Math.max(page,0),Math.min(Math.max(size,1),100));
  var selectedStatuses = statuses == null ? List.<String>of() : statuses.stream().filter(status -> status != null && !status.isBlank()).distinct().toList();
  var normalizedSearchBy = List.of("BUYER", "SELLER").contains(searchBy) ? searchBy : "ORDER_NUMBER";
  var from = fromDate == null ? null : fromDate.atStartOfDay(); var to = toDate == null ? null : toDate.plusDays(1).atStartOfDay();
  return orderRepository.searchAdminOrderLogs(query == null ? "" : query.trim(), normalizedSearchBy, selectedStatuses.isEmpty() ? List.of("__NO_STATUS_FILTER__") : selectedStatuses, selectedStatuses.isEmpty() ? "Y" : "N", from, to, pageable).map(AdminOrderLogResponse::from);
 }
}
