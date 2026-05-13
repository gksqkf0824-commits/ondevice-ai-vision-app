package com.example.demo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
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

    // INSERT 시점에 현재 시간 자동 저장
    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    // ✅ @JsonIgnore: LAZY 필드 직렬화 시 LazyInitializationException 방지
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    // JPA 기본 생성자
    public Record() {}

    // getter / setter
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public String getResultText() { return resultText; }
    public void setResultText(String resultText) { this.resultText = resultText; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
}