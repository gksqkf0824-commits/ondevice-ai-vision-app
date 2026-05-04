package com.example.demo.service;

import com.example.demo.entity.GuardianUser;
import com.example.demo.entity.User;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class HazardAlertService {

    private final SimpMessagingTemplate messagingTemplate;
    private final UserService userService;

    public HazardAlertService(SimpMessagingTemplate messagingTemplate,
                               UserService userService) {
        this.messagingTemplate = messagingTemplate;
        this.userService = userService;
    }

    // 보호자에게 WebSocket 알림 전송
    // 안드로이드 앱(보호자)은 /topic/alert/{guardianUsername} 을 구독하고 있어야 함
    public void sendAlertToGuardian(User personalUser, String objectType,
                                    double latitude, double longitude) {

        // 이 시각장애인과 연결된 보호자 찾기
        GuardianUser guardian = userService.findGuardianByLinkedUserId(personalUser.getId());
        if (guardian == null) return; // 연결된 보호자 없으면 종료

        // 알림 메시지 구성
        Map<String, Object> alert = new HashMap<>();
        alert.put("type", "HAZARD_ALERT");
        alert.put("objectType", objectType);
        alert.put("latitude", latitude);
        alert.put("longitude", longitude);
        alert.put("message", "[위험 알림] " + personalUser.getUsername() + " 사용자 근처에서 "
                + translateObjectType(objectType) + " 이(가) 반복 감지되었습니다.");

        // 보호자의 username으로 개인 채널에 전송
        messagingTemplate.convertAndSendToUser(
                guardian.getUsername(),
                "/queue/alert",
                alert
        );
    }

    // 객체 타입 한글 변환
    private String translateObjectType(String objectType) {
        return switch (objectType) {
            case "kickboard" -> "전동킥보드";
            case "bollard" -> "볼라드";
            case "bus" -> "버스";
            case "bus_door" -> "탑승문";
            default -> objectType;
        };
    }
}