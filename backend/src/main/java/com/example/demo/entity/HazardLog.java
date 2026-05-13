package com.example.demo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "hazard_logs")
public class HazardLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 탐지된 위험 객체 종류: "kickboard", "bollard", "bus_door", "bus" 등
    @Column(nullable = false)
    private String objectType;

    // GPS 위도
    @Column(nullable = false)
    private double latitude;

    // GPS 경도
    @Column(nullable = false)
    private double longitude;

    // 탐지 신뢰도 (YOLO confidence score, 0.0 ~ 1.0)
    private double confidence;

    // 탐지 시점 (자동 저장)
    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime detectedAt;

    // 어떤 사용자가 탐지했는지
    // ✅ @JsonIgnore: LAZY 필드 직렬화 시 LazyInitializationException 방지
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    // JPA 기본 생성자
    public HazardLog() {}

    // getter / setter
    public Long getId() { return id; }

    public String getObjectType() { return objectType; }
    public void setObjectType(String objectType) { this.objectType = objectType; }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public LocalDateTime getDetectedAt() { return detectedAt; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
}