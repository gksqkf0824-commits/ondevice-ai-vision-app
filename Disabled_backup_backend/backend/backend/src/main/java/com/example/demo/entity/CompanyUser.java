package com.example.demo.entity;

import jakarta.persistence.Entity;

@Entity
public class CompanyUser extends User {

    private String companyName;

    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
}