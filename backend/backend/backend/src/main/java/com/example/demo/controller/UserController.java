package com.example.demo.controller;

import com.example.demo.entity.CompanyUser;
import com.example.demo.entity.PersonalUser;
import com.example.demo.entity.User;
import com.example.demo.service.UserService;
import com.example.demo.util.JwtUtil;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;
    private final JwtUtil jwtUtil;

    public UserController(UserService userService, JwtUtil jwtUtil) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
    }

    // 로그인 -> 성공 시 JWT 토큰 반환
    @PostMapping("/login")
    public String login(@RequestParam String username,
                        @RequestParam String password) {
        User user = userService.login(username, password);
        if (user == null) return "로그인 실패";
        return jwtUtil.generateToken(username);
    }

    // 개인 회원가입
    @PostMapping("/signup/personal")
    public String signupPersonal(@RequestBody PersonalUser user) {
        userService.save(user);
        return "개인 회원가입 완료";
    }

    // 기업 회원가입
    @PostMapping("/signup/company")
    public String signupCompany(@RequestBody CompanyUser user) {
        userService.save(user);
        return "기업 회원가입 완료";
    }
}
