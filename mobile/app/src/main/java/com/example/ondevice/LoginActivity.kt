package com.example.ondevice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.ondevice.databinding.ActivityLoginBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var database: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(this)

        // AuthActivity에서 넘겨준 꼬리표(개인/기관/보호자)를 받습니다.
        val loginType = intent.getStringExtra("LOGIN_TYPE")?: "PERSONAL"

        // 💡 꼬리표에 따라 화면 맨 위의 큰 제목을 피그마 디자인처럼 바꿔줍니다!
        when (loginType) {
            "PERSONAL" -> binding.tvLoginTitle.text = "개인 로그인"
            "ORG" -> binding.tvLoginTitle.text = "기관 로그인"
            "GUARDIAN" -> binding.tvLoginTitle.text = "보호자 로그인"
        }

        binding.btnLoginConfirm.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val id = binding.etId.text.toString()
            val pw = binding.etPassword.text.toString()

            CoroutineScope(Dispatchers.IO).launch {
                val user = database.userDao().login(id, pw)
                withContext(Dispatchers.Main) {
                    if (user!= null) {
                        // 가입된 계정 유형과 누른 로그인 버튼의 유형이 다르면 막아냄
                        if (user.userType!= loginType) {
                            Toast.makeText(this@LoginActivity, "가입된 계정 유형과 다릅니다.", Toast.LENGTH_SHORT).show()
                            return@withContext
                        }

                        // 로그인 성공 시 기기에 내 정보 저장
                        val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                        with(sharedPref.edit()) {
                            putString("USER_ID", user.userId)
                            putString("USER_NAME", user.name)
                            putString("USER_TYPE", user.userType)
                            putString("GUARDIAN_ID", user.guardianId)
                            putString("ORG_NAME", user.orgName)
                            apply()
                        }

                        Toast.makeText(this@LoginActivity, "${user.name}님 환영합니다!", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                        finish()
                    } else {
                        Toast.makeText(this@LoginActivity, "아이디 또는 비밀번호가 틀렸습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        binding.btnCancel.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            finish()
        }
    }
}