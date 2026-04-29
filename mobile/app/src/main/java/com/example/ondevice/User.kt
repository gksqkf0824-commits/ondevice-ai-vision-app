package com.example.ondevice

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_table")
data class User(
    @PrimaryKey val userId: String,
    val name: String,
    val password: String,
    val userType: String = "PERSONAL", // PERSONAL, ORG, GUARDIAN
    val guardianId: String? = null     // 연동된 보호자의 아이디
)