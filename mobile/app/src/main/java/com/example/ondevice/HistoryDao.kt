package com.example.ondevice

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history_table ORDER BY timestamp DESC")
    fun getAllHistory(): List<History>

    //  사용자명 또는 사물 이름으로 검색하는 명령어
    @Query("SELECT * FROM history_table WHERE userName LIKE '%' || :searchQuery || '%' OR objectName LIKE '%' || :searchQuery || '%' ORDER BY timestamp DESC")
    fun searchHistory(searchQuery: String): List<History>

    @Insert
    fun insertHistory(history: History)
}