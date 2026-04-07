package com.example.demo.service;

import com.example.demo.entity.Record;
import com.example.demo.repository.RecordRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RecordService {

    private final RecordRepository recordRepository;

    public RecordService(RecordRepository recordRepository) {
        this.recordRepository = recordRepository;
    }

    public Record save(String imageUrl, String resultText) {
        Record record = new Record();
        record.setImageUrl(imageUrl);
        record.setResultText(resultText);
        return recordRepository.save(record);
    }

    public List<Record> findAll() {
        return recordRepository.findAll();
    }
}