package com.example.demo.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp; // 시간 자동화
import java.time.LocalDateTime;

@Entity
@Table(name = "records") 
public class Record {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String imageUrl;

    @Column(columnDefinition = "TEXT") 
    private String resultText;

    @CreationTimestamp // INSERT 시점에 현재 시간을 자동
    @Column(updatable = false)
    private LocalDateTime createdAt;

    // --- Getter / Setter (직접 작성) ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public String getResultText() { return resultText; }
    public void setResultText(String resultText) { this.resultText = resultText; }

    public LocalDateTime getCreatedAt() { return createdAt; }
  
}