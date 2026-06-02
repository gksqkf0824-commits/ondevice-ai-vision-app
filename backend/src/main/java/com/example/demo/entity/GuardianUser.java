package com.example.demo.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "guardian_user")
public class GuardianUser extends User {

    // 보호자 이름
    private String name;

    // 연결된 시각장애인 사용자 ID (PersonalUser의 id)
    // null이면 아직 연결 안 된 상태
    private Long linkedUserId;

    public GuardianUser() {
        setRole("GUARDIAN");
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Long getLinkedUserId() { return linkedUserId; }
    public void setLinkedUserId(Long linkedUserId) { this.linkedUserId = linkedUserId; }
}