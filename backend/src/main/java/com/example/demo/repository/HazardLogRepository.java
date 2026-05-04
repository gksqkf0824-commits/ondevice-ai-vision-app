package com.example.demo.repository;

import com.example.demo.entity.HazardLog;
import com.example.demo.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface HazardLogRepository extends JpaRepository<HazardLog, Long> {

    // 특정 사용자의 모든 위험 로그 조회
    List<HazardLog> findByUser(User user);

    // 특정 위치 반경 내 같은 objectType 탐지 횟수
    // 보호자 알림 트리거 판단에 사용 (위도/경도 ±0.0005 ≈ 반경 50m)
    @Query("SELECT COUNT(h) FROM HazardLog h " +
           "WHERE h.user.id = :userId " +
           "AND h.objectType = :objectType " +
           "AND ABS(h.latitude - :lat) < 0.0005 " +
           "AND ABS(h.longitude - :lng) < 0.0005 " +
           "AND h.detectedAt >= :since")
    long countNearbyHazards(
            @Param("userId") Long userId,
            @Param("objectType") String objectType,
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("since") LocalDateTime since
    );

    // 전체 위험 로그 조회 (지자체 대시보드용)
    List<HazardLog> findByDetectedAtBetween(LocalDateTime start, LocalDateTime end);

    // objectType별 위험 로그 수 집계 (대시보드 통계용)
    @Query("SELECT h.objectType, COUNT(h) FROM HazardLog h GROUP BY h.objectType")
    List<Object[]> countByObjectType();
}