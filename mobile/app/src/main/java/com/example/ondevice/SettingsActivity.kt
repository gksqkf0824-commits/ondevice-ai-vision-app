package com.example.ondevice

import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.example.ondevice.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. SharedPreferences에서 현재 로그인한 내 정보 불러오기
        val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val userId = sharedPref.getString("USER_ID", "알 수 없음")
        val userName = sharedPref.getString("USER_NAME", "설정 안 함")
        val userType = sharedPref.getString("USER_TYPE", "PERSONAL")

        // 2. 피그마 메모(Image 2) 로직 적용
        binding.tvIdValue.text = userId
        binding.tvNameValue.text = userName

        // "기관명은 일반 사용자(PERSONAL)의 경우 미소속"
        binding.tvOrgValue.text = if (userType == "PERSONAL") "미소속" else "설정 안 함"

        // "아이디를 제외한 사용자 정보 기본 설정값은 '설정 안 함'"
        binding.tvPhoneValue.text = "설정 안 함"
        binding.tvGuardianPhoneValue.text = "설정 안 함"

        // 3. 다크모드 스위치 실제 연동
        binding.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            window.decorView.rootView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            if (isChecked) {
                // 앱 전체를 다크모드로 변경
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                // 앱 전체를 라이트모드로 변경
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }

        // 알림 설정 스위치 진동 피드백
        binding.switchNotification.setOnCheckedChangeListener { _, _ ->
            window.decorView.rootView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }

        // 4. 뒤로 가기 처리
        binding.btnBack.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            finish()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                window.decorView.rootView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                finish()
            }
        })
    }
}