package com.example.demo.controller;

import com.example.demo.entity.Record;
import com.example.demo.entity.User;
import com.example.demo.service.RecordService;
import com.example.demo.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/records")
public class RecordController {

    private final RecordService recordService;
    private final UserService userService;

    // ✅ 문제3 수정: 사용 안 하는 JwtUtil 파라미터 제거
    public RecordController(RecordService recordService, UserService userService) {
        this.recordService = recordService;
        this.userService = userService;
    }

    // 내 이용 기록 조회 (GET /api/records/my)
    @GetMapping("/my")
    public ResponseEntity<?> getMyRecords(@AuthenticationPrincipal String username) {

        if (username == null) {
            return ResponseEntity.status(401).body(Map.of("message", "인증 정보가 없습니다."));
        }

        User user = userService.findByUsername(username);
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("message", "사용자를 찾을 수 없습니다."));
        }

        List<Record> myRecords = recordService.findByUser(user);
        return ResponseEntity.ok(myRecords);
    }
}