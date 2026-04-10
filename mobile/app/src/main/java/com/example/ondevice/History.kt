package com.example.ondevice

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "history_table")
data class History(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userName: String,                             //  사용자명 (예: 사용자1)
    val objectName: String,                           // 인식한 대상 (예: 서울 우유)
    val description: String,                          // 💡 추가됨: 상세 설명
    val timestamp: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false
)