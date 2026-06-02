package com.example.ondevice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.ondevice.databinding.ActivityBusInputBinding
import java.util.Locale

class BusInputActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBusInputBinding

    // 안드로이드 기본 음성 인식(STT) 결과를 받아오는 도구
    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = data?.get(0)?: ""
            // 한글 섞임 방지: 숫자만 추출하여 입력창에 넣기
            val numberOnly = spokenText.replace(Regex("[^0-9]"), "")
            binding.etBusNumber.setText(numberOnly)
            Toast.makeText(this, "인식된 번호: $numberOnly", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBusInputBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 마이크 버튼 클릭 -> STT 실행
        binding.btnMic.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "탑승하실 버스 번호를 말해주세요.")
            }
            speechLauncher.launch(intent)
        }

        // 확인 버튼 -> 번호 들고 카메라 화면으로 이동
        binding.btnConfirm.setOnClickListener {
            val busNum = binding.etBusNumber.text.toString()
            if (busNum.isNotEmpty()) {
                val intent = Intent(this, CameraActivity::class.java)
                intent.putExtra("TARGET_BUS", busNum) // 타겟 버스 번호 전달
                startActivity(intent)
                finish()
            } else {
                Toast.makeText(this, "버스 번호를 입력하거나 마이크로 말해주세요.", Toast.LENGTH_SHORT).show()
            }
        }

        // 건너뛰기 -> 일반 인식 모드
        binding.btnSkip.setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
            finish()
        }
    }
}