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

    // (추가된 부분) 연관관계 ; 기록은 User가 누군지 저장
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id") // 외래키(FK) 생성
    private User user;

    // JPA 기본 생성자
    public Record(){}

    // --- Getter / Setter (직접 작성) ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public String getResultText() { return resultText; }
    public void setResultText(String resultText) { this.resultText = resultText; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    // 추가된 Getter/Setter
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
  
}