package com.example.ondevice

import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.appcompat.app.AppCompatActivity
import com.example.ondevice.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. '카메라 인식' 버튼 -> 버스 번호 입력 화면(BusInputActivity)으로 이동
        binding.btnGoCamera.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            startActivity(Intent(this, BusInputActivity::class.java))
        }

        // 2. '기록 확인' 버튼 -> 인식 기록 화면(HistoryActivity)으로 이동
        binding.btnGoHistory.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        // 3. '설정' 버튼 -> 설정 화면(SettingsActivity)으로 이동
        binding.btnGoSettings.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.navCamera.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            startActivity(Intent(this, BusInputActivity::class.java))
        }

        binding.navSettings.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
}