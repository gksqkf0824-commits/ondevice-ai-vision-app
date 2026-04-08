package com.example.demo.service;

import com.example.demo.entity.Record;
import com.example.demo.repository.RecordRepository;
import com.example.demo.entity.User;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RecordService {

    private final RecordRepository recordRepository;

    public RecordService(RecordRepository recordRepository) {
        this.recordRepository = recordRepository;
    }

    // 기록 저장 (나중에 여기에 record.setUser(user)도 추가해야 해!)
    public Record save(String imageUrl, String resultText) {
        Record record = new Record();
        record.setImageUrl(imageUrl);
        record.setResultText(resultText);
        return recordRepository.save(record);
    }

    // 모든 기록 조회
    public List<Record> findAll() {
        return recordRepository.findAll();
    }

    // 특정 사용자별 기록 조회
    public List<Record> findByUser(User user) {
        return recordRepository.findByUser(user);
    }
}