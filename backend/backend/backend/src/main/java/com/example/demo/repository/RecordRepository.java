package com.example.demo.repository;

import com.example.demo.entity.Record;
import com.example.demo.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RecordRepository extends JpaRepository<Record, Long> {

    List<Record> findByUser(User user);
}