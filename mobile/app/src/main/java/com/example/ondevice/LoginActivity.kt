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

        binding.btnLoginConfirm.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val id = binding.etId.text.toString()
            val pw = binding.etPassword.text.toString()

            // 💡 입력한 정보로 DB에서 유저 찾기
            CoroutineScope(Dispatchers.IO).launch {
                val user = database.userDao().login(id, pw)
                withContext(Dispatchers.Main) {
                    if (user!= null) {
                        Toast.makeText(this@LoginActivity, "${user.name}님 환영합니다!", Toast.LENGTH_SHORT).show()

                        // 로그인 성공 시 내 정보를 스마트폰에 기억시킵니다.
                        val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                        with(sharedPref.edit()) {
                            putString("USER_ID", user.userId)
                            putString("USER_NAME", user.name)
                            putString("USER_TYPE", user.userType) // 개인(PERSONAL)인지 기관인지 구분
                            apply()
                        }

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