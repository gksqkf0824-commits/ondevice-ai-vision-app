package com.example.demo.controller;

import com.example.demo.entity.HazardLog;
import com.example.demo.entity.User;
import com.example.demo.service.HazardLogService;
import com.example.demo.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/hazard")
public class HazardLogController {

    private final HazardLogService hazardLogService;
    private final UserService userService;

    public HazardLogController(HazardLogService hazardLogService, UserService userService) {
        this.hazardLogService = hazardLogService;
        this.userService = userService;
    }

    // 위험 객체 탐지 로그 전송 (안드로이드 → 서버)
    // Body 예시:
    // {
    //   "objectType": "kickboard",
    //   "latitude": 37.4913,
    //   "longitude": 127.0301,
    //   "confidence": 0.87
    // }
    @PostMapping
    public ResponseEntity<?> saveHazardLog(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal String username) {

        if (username == null) {
            return ResponseEntity.status(401).body(Map.of("message", "인증 정보가 없습니다."));
        }

        User user = userService.findByUsername(username);
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("message", "사용자를 찾을 수 없습니다."));
        }

        // ✅ 문제2 수정: latitude / longitude null 체크 추가
        String objectType = (String) body.get("objectType");
        Object latObj = body.get("latitude");
        Object lngObj = body.get("longitude");

        if (objectType == null || objectType.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "objectType은 필수입니다."));
        }
        if (latObj == null || lngObj == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "latitude와 longitude는 필수입니다."));
        }

        double latitude  = ((Number) latObj).doubleValue();
        double longitude = ((Number) lngObj).doubleValue();
        double confidence = body.containsKey("confidence")
                ? ((Number) body.get("confidence")).doubleValue()
                : 0.0;

        HazardLog saved = hazardLogService.save(objectType, latitude, longitude, confidence, user);

        return ResponseEntity.ok(Map.of(
                "message", "위험 로그 저장 완료",
                "id", saved.getId(),
                "objectType", saved.getObjectType(),
                "detectedAt", saved.getDetectedAt().toString()
        ));
    }

    // 내 위험 로그 조회
    @GetMapping("/my")
    public ResponseEntity<?> getMyHazardLogs(@AuthenticationPrincipal String username) {

        if (username == null) {
            return ResponseEntity.status(401).body(Map.of("message", "인증 정보가 없습니다."));
        }

        User user = userService.findByUsername(username);
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("message", "사용자를 찾을 수 없습니다."));
        }

        List<HazardLog> logs = hazardLogService.findByUser(user);
        return ResponseEntity.ok(logs);
    }
}