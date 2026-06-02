package com.example.demo.controller;

import com.example.demo.entity.Record;
import com.example.demo.service.RecordService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.List;

@RestController
@RequestMapping("/api")
public class ImageController {

    private final RecordService recordService;

    public ImageController(RecordService recordService) {
        this.recordService = recordService;
    }

    // 이미지 업로드
    @PostMapping("/image")
    public String upload(@RequestParam("file") MultipartFile file) {

        try {
            // 파일 이름 생성
            String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();

            // 저장 경로 (프로젝트 루트 기준)
            String uploadDir = System.getProperty("user.dir") + "/uploads/";

            // 폴더 없으면 생성
            File dir = new File(uploadDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            // 파일 저장
            File saveFile = new File(uploadDir + fileName);
            file.transferTo(saveFile);

            // DB 저장
            String result = "테스트 결과입니다";
            recordService.save("uploads/" + fileName, result);

            return "파일 업로드 성공";

        } catch (Exception e) {
            e.printStackTrace();
            return "파일 업로드 실패";
        }
    }

    // 기록 조회
    @GetMapping("/records")
    public List<Record> getRecords() {
        return recordService.findAll();
    }
}