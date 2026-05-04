package com.example.demo.controller;

import com.example.demo.entity.HazardLog;
import com.example.demo.service.HazardLogService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// ⚠️ SecurityConfig에서 COMPANY 권한만 접근 가능하도록 설정돼 있음
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final HazardLogService hazardLogService;

    public DashboardController(HazardLogService hazardLogService) {
        this.hazardLogService = hazardLogService;
    }

    // 전체 위험 로그 목록 (지자체 지도 표시용)
    // 파라미터 없으면 전체, 있으면 기간 필터
    // 예: GET /api/dashboard/hazards?start=2026-04-01T00:00:00&end=2026-04-30T23:59:59
    @GetMapping("/hazards")
    public ResponseEntity<?> getHazards(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {

        List<HazardLog> logs;

        if (start != null && end != null) {
            logs = hazardLogService.findByPeriod(start, end);
        } else {
            logs = hazardLogService.findAll();
        }

        return ResponseEntity.ok(logs);
    }

    // 위험물 종류별 통계 (지자체 대시보드 차트용)
    // 반환 예시: { "kickboard": 42, "bollard": 18, "bus": 5 }
    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        List<Object[]> raw = hazardLogService.countByObjectType();

        Map<String, Long> stats = new HashMap<>();
        for (Object[] row : raw) {
            String objectType = (String) row[0];
            Long count = (Long) row[1];
            stats.put(objectType, count);
        }

        return ResponseEntity.ok(stats);
    }
}