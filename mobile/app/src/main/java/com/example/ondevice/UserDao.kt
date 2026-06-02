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
    @Query("SELECT * FROM user_table WHERE username = :id AND password = :pw")
    fun login(id: String, pw: String): User?

    // 3. 아이디 중복 확인용
    @Query("SELECT * FROM user_table WHERE username = :id")
    fun checkIdExist(id: String): User?

    //  아이디로 특정 유저 검색
    @Query("SELECT * FROM user_table WHERE username = :id")
    fun getUser(id: String): User?

    //  내 정보에 보호자 아이디를 등록(연동)
    @Query("UPDATE user_table SET guardianId = :guardianId WHERE username = :userId")
    fun linkGuardian(userId: String, guardianId: String)
}