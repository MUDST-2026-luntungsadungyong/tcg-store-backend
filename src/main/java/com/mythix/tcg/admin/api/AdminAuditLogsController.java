package com.mythix.tcg.admin.api;

import com.mythix.tcg.admin.persistence.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/admin/audit-logs")
@RequiredArgsConstructor
public class AdminAuditLogsController {
    private final AuditLogRepository logs;

    @GetMapping
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> list(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "ALL") String searchBy,
            @RequestParam(required = false) List<String> actions,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));

        List<String> combinedActions = new ArrayList<>();
        if (actions != null) {
            for (String a : actions) {
                if (a != null && !a.isBlank()) {
                    for (String part : a.split(",")) {
                        String trimmed = part.trim();
                        if (!trimmed.isEmpty() && !combinedActions.contains(trimmed)) {
                            combinedActions.add(trimmed);
                        }
                    }
                }
            }
        }
        if (action != null && !action.isBlank()) {
            for (String part : action.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && !combinedActions.contains(trimmed)) {
                    combinedActions.add(trimmed);
                }
            }
        }

        var normalizedSearchBy = List.of("ACTOR", "TARGET", "DETAILS").contains(searchBy) ? searchBy : "ALL";
        var from = fromDate == null ? null : fromDate.atStartOfDay();
        var to = toDate == null ? null : toDate.plusDays(1).atStartOfDay();

        return logs.searchAuditLogs(
                query == null ? "" : query.trim(),
                normalizedSearchBy,
                combinedActions.isEmpty() ? List.of("__NO_ACTION_FILTER__") : combinedActions,
                combinedActions.isEmpty() ? "Y" : "N",
                from,
                to,
                pageable
        ).map(AuditLogResponse::from);
    }
}
