package com.example.demo.service;

import com.example.demo.entity.HazardLog;
import com.example.demo.entity.User;
import com.example.demo.repository.HazardLogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class HazardLogService {

    private final HazardLogRepository hazardLogRepository;
    private final HazardAlertService hazardAlertService;

    // application.properties에서 임계값 주입 (기본값 3)
    @Value("${hazard.alert-threshold:3}")
    private int alertThreshold;

    public HazardLogService(HazardLogRepository hazardLogRepository,
                            HazardAlertService hazardAlertService
                            ) {
        this.hazardLogRepository = hazardLogRepository;
        this.hazardAlertService = hazardAlertService;
    }

    // 위험 로그 저장 + 보호자 알림 트리거 판단
    public HazardLog save(String objectType, double latitude, double longitude, double confidence, User user) {

        // 1. 위험 로그 저장
        HazardLog log = new HazardLog();
        log.setObjectType(objectType);
        log.setLatitude(latitude);
        log.setLongitude(longitude);
        log.setConfidence(confidence);
        log.setUser(user);
        HazardLog saved = hazardLogRepository.save(log);

        // 2. 같은 위치에서 최근 30분 내 동일 위험물이 임계값 이상 감지됐는지 확인
        LocalDateTime since = LocalDateTime.now().minusMinutes(30);
        long count = hazardLogRepository.countNearbyHazards(
                user.getId(), objectType, latitude, longitude, since
        );

        // 3. 임계값 초과 시 보호자에게 WebSocket 알림 전송
        if (count >= alertThreshold) {
            hazardAlertService.sendAlertToGuardian(user, objectType, latitude, longitude);
        }

        return saved;
    }

    // 특정 사용자의 위험 로그 전체 조회
    public List<HazardLog> findByUser(User user) {
        return hazardLogRepository.findByUser(user);
    }

    // 전체 위험 로그 조회 (지자체 대시보드용)
    public List<HazardLog> findAll() {
        return hazardLogRepository.findAll();
    }

    // 기간별 위험 로그 조회 (지자체 대시보드용)
    public List<HazardLog> findByPeriod(LocalDateTime start, LocalDateTime end) {
        return hazardLogRepository.findByDetectedAtBetween(start, end);
    }

    // objectType별 집계 (지자체 대시보드용)
    public List<Object[]> countByObjectType() {
        return hazardLogRepository.countByObjectType();
    }
}