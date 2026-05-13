package com.example.demo.entity;

import jakarta.persistence.Entity;

@Entity
public class PersonalUser extends User {

    private String name;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}