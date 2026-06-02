package com.example.demo.controller;

import com.example.demo.entity.CompanyUser;
import com.example.demo.entity.GuardianUser;
import com.example.demo.entity.PersonalUser;
import com.example.demo.entity.User;
import com.example.demo.service.UserService;
import com.example.demo.util.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal; // ✅ 버그1 수정: import 추가
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;
    private final JwtUtil jwtUtil;

    public UserController(UserService userService, JwtUtil jwtUtil) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
    }

    // 로그인 → 성공 시 JWT 토큰 + role 반환
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestParam String username,
                                   @RequestParam String password) {
        User user = userService.login(username, password);
        if (user == null) {
            return ResponseEntity.status(401).body(Map.of("message", "아이디 또는 비밀번호가 틀렸습니다."));
        }
        String token = jwtUtil.generateToken(username);
        return ResponseEntity.ok(Map.of(
                "token", token,
                "role", user.getRole(),
                "username", user.getUsername()
        ));
    }

    // 개인(시각장애인) 회원가입
    @PostMapping("/signup/personal")
    public ResponseEntity<?> signupPersonal(@RequestBody PersonalUser user) {
        if (userService.findByUsername(user.getUsername()) != null) {
            return ResponseEntity.badRequest().body(Map.of("message", "이미 존재하는 아이디입니다."));
        }
        userService.save(user);
        return ResponseEntity.ok(Map.of("message", "개인 회원가입 완료"));
    }

    // 기관 회원가입
    @PostMapping("/signup/company")
    public ResponseEntity<?> signupCompany(@RequestBody CompanyUser user) {
        if (userService.findByUsername(user.getUsername()) != null) {
            return ResponseEntity.badRequest().body(Map.of("message", "이미 존재하는 아이디입니다."));
        }
        userService.save(user);
        return ResponseEntity.ok(Map.of("message", "기관 회원가입 완료"));
    }

    // 보호자 회원가입
    @PostMapping("/signup/guardian")
    public ResponseEntity<?> signupGuardian(@RequestBody GuardianUser user) {
        if (userService.findByUsername(user.getUsername()) != null) {
            return ResponseEntity.badRequest().body(Map.of("message", "이미 존재하는 아이디입니다."));
        }
        userService.save(user);
        return ResponseEntity.ok(Map.of("message", "보호자 회원가입 완료"));
    }

    // 보호자-시각장애인 연결
    @PostMapping("/guardian/link")
    public ResponseEntity<?> linkGuardian(
            @AuthenticationPrincipal String username,
            @RequestParam Long personalUserId) {

        User me = userService.findByUsername(username);
        if (!(me instanceof GuardianUser guardian)) {
            return ResponseEntity.badRequest().body(Map.of("message", "보호자 계정만 사용 가능합니다."));
        }

        User personalUser = userService.findById(personalUserId);
        if (personalUser == null) {
            return ResponseEntity.status(404).body(Map.of("message", "연결할 사용자를 찾을 수 없습니다."));
        }

        guardian.setLinkedUserId(personalUserId);
        userService.updateLink(guardian); // ✅ 버그2 수정: 비밀번호 재암호화 없이 업데이트하는 메서드 사용
        return ResponseEntity.ok(Map.of("message", "보호자 연결 완료"));
    }
}