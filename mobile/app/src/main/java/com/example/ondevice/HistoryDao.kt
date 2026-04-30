package com.example.ondevice

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface HistoryDao {
    // 💡 1. 개인/기관 사용자용: 본인 아이디(userId)와 일치하는 기록만 가져오기
    @Query("SELECT * FROM history_table WHERE userName = :userId ORDER BY timestamp DESC")
    fun getHistoryForUser(userId: String): List<History>

    @Query("SELECT * FROM history_table WHERE userName = :userId AND objectName LIKE '%' || :searchQuery || '%' ORDER BY timestamp DESC")
    fun searchHistoryForUser(userId: String, searchQuery: String): List<History>

    // 💡 2. 보호자용: 내 아이디를 보호자로 등록해둔 하위 사용자들의 기록만 가져오기 (Subquery 활용)
    @Query("SELECT * FROM history_table WHERE userName IN (SELECT userId FROM user_table WHERE guardianId = :guardianId) ORDER BY timestamp DESC")
    fun getHistoryForGuardian(guardianId: String): List<History>

    @Query("SELECT * FROM history_table WHERE userName IN (SELECT userId FROM user_table WHERE guardianId = :guardianId) AND (userName LIKE '%' || :searchQuery || '%' OR objectName LIKE '%' || :searchQuery || '%') ORDER BY timestamp DESC")
    fun searchHistoryForGuardian(guardianId: String, searchQuery: String): List<History>

    @Insert
    fun insertHistory(history: History)
}