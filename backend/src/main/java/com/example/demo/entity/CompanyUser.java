package com.example.demo.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "company_user")
public class CompanyUser extends User {

    // 기관명
    private String companyName;

    // 담당자명
    private String managerName;

    public CompanyUser() {
        setRole("COMPANY");
    }

    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }

    public String getManagerName() { return managerName; }
    public void setManagerName(String managerName) { this.managerName = managerName; }
}