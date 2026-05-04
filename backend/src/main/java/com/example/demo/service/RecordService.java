package com.example.demo.service;

import com.example.demo.entity.Record;
import com.example.demo.entity.User;
import com.example.demo.repository.RecordRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RecordService {

    private final RecordRepository recordRepository;

    public RecordService(RecordRepository recordRepository) {
        this.recordRepository = recordRepository;
    }

    // ✅ 버그 수정: user를 받아서 record에 연결
    public Record save(String imageUrl, String resultText, User user) {
        Record record = new Record();
        record.setImageUrl(imageUrl);
        record.setResultText(resultText);
        record.setUser(user);   // 이전에 빠져있던 부분
        return recordRepository.save(record);
    }

    // 모든 기록 조회 (관리자용)
    public List<Record> findAll() {
        return recordRepository.findAll();
    }

    // 특정 사용자별 기록 조회
    public List<Record> findByUser(User user) {
        return recordRepository.findByUser(user);
    }
}