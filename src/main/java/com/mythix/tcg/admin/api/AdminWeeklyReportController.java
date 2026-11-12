package com.mythix.tcg.admin.api;

import com.mythix.tcg.admin.application.WeeklyReportCsv;
import com.mythix.tcg.admin.application.WeeklyReportService;
import com.mythix.tcg.common.api.ApiError;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/reports/weekly-summary")
@RequiredArgsConstructor
public class AdminWeeklyReportController {
    private final WeeklyReportService reports;

    @GetMapping
    public ResponseEntity<WeeklyReportDto.Report> report(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekEnd) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.get(weekEnd));
    }

    @GetMapping("/history")
    public ResponseEntity<WeeklyReportDto.History> history(
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reports.history(page, size));
    }

    @GetMapping(value = "/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> export(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekEnd) {
        var report = reports.get(weekEnd);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"weekly-report-" + report.weekEndDate() + ".csv\"")
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .body(WeeklyReportCsv.export(report));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> invalidReport(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(new ApiError(
            exception.getStatusCode().value() == 404 ? "WEEKLY_REPORT_NOT_FOUND" : "INVALID_REPORT_REQUEST",
            exception.getReason(), Map.of()));
    }
}
