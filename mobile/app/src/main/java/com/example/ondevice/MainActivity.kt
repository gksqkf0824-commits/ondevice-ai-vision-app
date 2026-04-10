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

        // '카메라 인식' 버튼을 눌렀을 때
        binding.btnGoCamera.setOnClickListener { view ->
            // 진동 피드백
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

            // CameraActivity로 화면 이동
            val intent = Intent(this, CameraActivity::class.java)
            startActivity(intent)
        }

        // '이미지 첨부' 버튼을 눌렀을 때
        binding.btnGoGallery.setOnClickListener { view ->
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

            // CameraActivity로 이동하면서 "갤러리를 열어라"라는 신호(OPEN_GALLERY)를 같이 보냄
            val intent = Intent(this, CameraActivity::class.java)
            intent.putExtra("OPEN_GALLERY", true)
            startActivity(intent)
        }

        // '인식 기록 확인' 버튼을 눌렀을 때
        binding.btnGoHistory.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

            // 💡 Toast 대신 HistoryActivity로 화면 이동!
            val intent = Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        }
        // (MainActivity.kt 내부)
        binding.btnGoSettings.setOnClickListener { view ->
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

            // 💡 Toast 대신 SettingsActivity로 화면 이동!
            val intent = android.content.Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
    }
}