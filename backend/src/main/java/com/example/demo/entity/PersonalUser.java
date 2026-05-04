package com.example.demo.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "personal_user")
public class PersonalUser extends User {

    // 실제 이름 (username은 아이디)
    private String name;

    public PersonalUser() {
        setRole("PERSONAL");
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}