package com.example.ondevice

import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.appcompat.app.AppCompatActivity
import com.example.ondevice.databinding.ActivityAuthBinding

class AuthActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAuthBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. '로그인' 버튼 클릭 시
        binding.btnLoginSelection.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            startActivity(Intent(this, LoginActivity::class.java))
        }

        // 2. '개인 이용자 회원가입' 버튼 클릭 시
        binding.btnSignupPersonal.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            startActivity(Intent(this, SignupActivity::class.java))
        }

        // 3. 나머지 기관 가입 버튼 (추후 구현을 위해 진동만)
        binding.btnSignupNewOrg.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
        binding.btnSignupOrgUser.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    }
}