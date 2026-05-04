package com.example.demo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 클라이언트가 구독할 prefix (서버 → 클라이언트 방향)
        // /topic : 브로드캐스트
        // /queue : 개인(1:1) 메시지
        config.enableSimpleBroker("/topic", "/queue");

        // 클라이언트가 서버로 메시지 보낼 때 prefix (클라이언트 → 서버 방향)
        config.setApplicationDestinationPrefixes("/app");

        // convertAndSendToUser() 에서 사용하는 prefix
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 안드로이드 앱이 WebSocket 연결할 엔드포인트
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*") // 개발 중에는 전체 허용
                .withSockJS();                  // SockJS 폴백 지원
    }
}