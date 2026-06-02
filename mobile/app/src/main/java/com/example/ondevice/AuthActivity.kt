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

        binding.btnLoginPersonal.setOnClickListener { navigateToLogin("PERSONAL") }
        binding.btnLoginOrg.setOnClickListener { navigateToLogin("ORG") }
        binding.btnLoginGuardian.setOnClickListener { navigateToLogin("GUARDIAN") }

        binding.tvGoSignup.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            startActivity(Intent(this, SignupActivity::class.java))
        }
    }

    private fun navigateToLogin(type: String) {
        window.decorView.rootView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val intent = Intent(this, LoginActivity::class.java)
        intent.putExtra("LOGIN_TYPE", type) // 어떤 로그인인지 전달
        startActivity(intent)
    }
}