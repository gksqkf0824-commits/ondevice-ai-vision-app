package com.example.ondevice

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_table")
data class User(
    @PrimaryKey
    val username: String,
    val password: String,
    val name: String,
    val role: String,
    val orgName: String? = null,
    val guardianId: String? = null
)