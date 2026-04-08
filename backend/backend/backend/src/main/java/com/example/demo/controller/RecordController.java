package com.example.demo.controller;

import com.example.demo.entity.Record;
import com.example.demo.entity.User;
import com.example.demo.service.RecordService;
import com.example.demo.service.UserService;
import com.example.demo.util.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.util.List;

@RestController
@RequestMapping("/api/records")
public class RecordController {

    private final RecordService recordService;
    private final UserService userService;

    // 필요한 서비스들을 생성자로 주입받기
    public RecordController(RecordService recordService, UserService userService, JwtUtil jwtUtil) {
        this.recordService = recordService;
        this.userService = userService;
    }

    // 사용자별 기록 조회 API (GET /api/records/my)
    @GetMapping("/my")
    public ResponseEntity<?> getMyRecords(@AuthenticationPrincipal String username) {
        // 1. JwtFilter가 SecurityContext에 넣어준 username을 @AuthenticationPrincipal로 바로 받음!
        if (username == null) {
            return ResponseEntity.status(401).body("인증 정보가 없습니다.");
        }

        // 아이디로 유저 객체 찾기
        User user = userService.findByUsername(username);

        if (user == null) {
            return ResponseEntity.status(404).body("사용자를 찾을 수 없습니다.");
        }

        // 해당 유저의 기록만 조회해서 반환
        List<Record> myRecords = recordService.findByUser(user);
        return ResponseEntity.ok(myRecords);
    }
}