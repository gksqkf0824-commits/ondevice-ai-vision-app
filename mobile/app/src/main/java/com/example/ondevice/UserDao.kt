package com.example.ondevice

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface UserDao {
    // 1. 회원가입: 새 유저 추가
    @Insert
    fun insertUser(user: User)

    // 2. 로그인: 아이디와 비밀번호가 일치하는 유저 찾기
    @Query("SELECT * FROM user_table WHERE userId = :id AND password = :pw")
    fun login(id: String, pw: String): User?

    // 3. 아이디 중복 확인용
    @Query("SELECT * FROM user_table WHERE userId = :id")
    fun checkIdExist(id: String): User?
}