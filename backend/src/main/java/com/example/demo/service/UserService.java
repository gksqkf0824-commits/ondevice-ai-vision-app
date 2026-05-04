package com.example.demo.service;

import com.example.demo.entity.GuardianUser;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public User findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public User findById(Long id) {
        return userRepository.findById(id).orElse(null);
    }

    public User login(String username, String password) {
        User user = userRepository.findByUsername(username);
        if (user == null) return null;
        if (!passwordEncoder.matches(password, user.getPassword())) return null;
        return user;
    }

    // 회원가입 전용 save: 비밀번호를 암호화한 뒤 저장
    public User save(User user) {
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        return userRepository.save(user);
    }

    // ✅ 버그2 수정: 비밀번호를 건드리지 않고 다른 필드만 업데이트할 때 사용
    // linkGuardian 같이 비밀번호 변경 없이 저장이 필요한 경우에 사용
    public User updateLink(User user) {
        return userRepository.save(user); // 비밀번호 재암호화 없이 그대로 저장
    }

    // 특정 시각장애인과 연결된 보호자 조회 (WebSocket 알림용)
    public GuardianUser findGuardianByLinkedUserId(Long personalUserId) {
        return userRepository.findAll().stream()
                .filter(u -> u instanceof GuardianUser)
                .map(u -> (GuardianUser) u)
                .filter(g -> personalUserId.equals(g.getLinkedUserId()))
                .findFirst()
                .orElse(null);
    }
}