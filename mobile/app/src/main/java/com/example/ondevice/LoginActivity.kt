package com.example.ondevice

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.ondevice.databinding.ActivityLoginBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.ondevice.network.RetrofitClient
import com.example.ondevice.network.LoginResponse

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

        when (loginType) {
            "PERSONAL" -> binding.tvLoginTitle.text = "개인 로그인"
            "ORG" -> binding.tvLoginTitle.text = "기관 로그인"
            "GUARDIAN" -> binding.tvLoginTitle.text = "보호자 로그인"
        }

        val activeTabBg = ContextCompat.getDrawable(this, R.drawable.bg_tab_active_yellow)
        val activeTextColor = Color.parseColor("#111111")
        when (loginType) {
            "PERSONAL" -> {
                binding.tabLoginPersonal.background = activeTabBg
                binding.tabLoginPersonal.setTextColor(activeTextColor)
            }
            "ORG" -> {
                binding.tabLoginOrg.background = activeTabBg
                binding.tabLoginOrg.setTextColor(activeTextColor)
            }
            "GUARDIAN" -> {
                binding.tabLoginGuardian.background = activeTabBg
                binding.tabLoginGuardian.setTextColor(activeTextColor)
            }
        }

        binding.btnLoginConfirm.setOnClickListener {
            val id = binding.etId.text.toString()
            val pw = binding.etPassword.text.toString()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val response = RetrofitClient.instance.login(id, pw) // 백엔드 호출

                    // ✅ 1. 네트워크 응답 확인 및 DB 작업은 계속 IO 스레드에서 진행합니다.
                    if (response.isSuccessful && response.body() != null) {
                        val loginData = response.body()!!

                        // 기존 로컬 DB에 유저가 있는지 확인 (재로그인 시 튕김 방지)
                        val existingUser = database.userDao().getUser(loginData.username)

                        if (existingUser == null) {
                            val localUser = User(
                                username = loginData.username,
                                password = pw, // 로컬 캐싱용
                                name = loginData.username, // 백엔드 응답에 이름이 없다면 임시로 아이디를 이름으로 사용
                                role = loginData.role
                            )
                            database.userDao().insertUser(localUser)
                        }

                        // ✅ 2. 화면 이동, 토스트 메시지, SharedPreferences 등은 메인 스레드에서 진행합니다.
                        withContext(Dispatchers.Main) {
                            val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                            with(sharedPref.edit()) {
                                putString("USER_ID", loginData.username)
                                putString("USER_TYPE", loginData.role)
                                putString("ACCESS_TOKEN", loginData.token) // JWT 토큰 저장
                                apply()
                            }

                            Toast.makeText(this@LoginActivity, "환영합니다!", Toast.LENGTH_SHORT).show()
                            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                            finish()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@LoginActivity, "아이디 또는 비밀번호가 틀렸습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        // 에러 발생 시 로그캣에 진짜 원인을 찍어줍니다.
                        android.util.Log.e("LoginError", "로그인 실패: ", e)
                        Toast.makeText(this@LoginActivity, "서버 연결 실패: ${e.message}", Toast.LENGTH_SHORT).show()
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