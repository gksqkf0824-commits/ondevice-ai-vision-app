package com.example.ondevice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

// 💡 2초 대기 후 로그인 상태에 따라 이동할 화면 결정
        Handler(Looper.getMainLooper()).postDelayed({
            val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val isLoggedIn = sharedPref.getString("USER_ID", null)!= null

            val intent = if (isLoggedIn) {
                Intent(this, MainActivity::class.java) // 이미 로그인된 경우 메인 화면으로 직행
            } else {
                Intent(this, AuthActivity::class.java) // 로그인 안 된 경우 선택창으로
            }
            startActivity(intent)
            finish()
        }, 2000)
    }
}