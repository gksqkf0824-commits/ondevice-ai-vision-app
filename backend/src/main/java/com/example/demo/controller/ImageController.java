package com.example.demo.controller;

import com.example.demo.entity.Record;
import com.example.demo.entity.User;
import com.example.demo.service.RecordService;
import com.example.demo.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ImageController {

    private final RecordService recordService;
    private final UserService userService;

    @Value("${file.upload-dir:uploads}")
    private String uploadDir;

    public ImageController(RecordService recordService, UserService userService) {
        this.recordService = recordService;
        this.userService = userService;
    }

    // 이미지 업로드 + 탐지 결과 저장
    // 안드로이드에서: multipart/form-data로 file + resultText 같이 보내야 함
    @PostMapping("/image")
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "resultText", defaultValue = "") String resultText,
            @AuthenticationPrincipal String username) {

        try {
            // 현재 로그인 사용자 조회
            User user = userService.findByUsername(username);
            if (user == null) {
                return ResponseEntity.status(401).body(Map.of("message", "인증 정보가 없습니다."));
            }

            // 파일명 생성 (timestamp + 원본 파일명)
            String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();

            // 저장 폴더 생성
            String fullUploadDir = System.getProperty("user.dir") + "/" + uploadDir + "/";
            File dir = new File(fullUploadDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            // 파일 저장
            File saveFile = new File(fullUploadDir + fileName);
            file.transferTo(saveFile);

            // ✅ 버그 수정: user를 연결해서 DB 저장
            String imageUrl = uploadDir + "/" + fileName;
            recordService.save(imageUrl, resultText, user);

            return ResponseEntity.ok(Map.of(
                    "message", "업로드 성공",
                    "imageUrl", imageUrl
            ));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("message", "업로드 실패: " + e.getMessage()));
        }
    }

    // 전체 기록 조회 (관리자용)
    @GetMapping("/records")
    public List<Record> getRecords() {
        return recordService.findAll();
    }
}