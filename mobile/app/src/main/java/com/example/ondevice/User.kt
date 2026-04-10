package com.example.ondevice

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_table")
data class User(
    @PrimaryKey val userId: String, // 아이디를 기본키(중복 불가)로 사용
    val name: String,
    val password: String,
    val userType: String = "PERSONAL" // 개인인지 기관인지 구분
)