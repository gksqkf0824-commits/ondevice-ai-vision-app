package com.example.ondevice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.ondevice.databinding.ActivitySettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var database: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(this)

        val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val userId = sharedPref.getString("USER_ID", "알 수 없음")?: ""
        val userName = sharedPref.getString("USER_NAME", "설정 안 함")
        val userType = sharedPref.getString("USER_TYPE", "PERSONAL")
        val guardianId = sharedPref.getString("GUARDIAN_ID", null)

        binding.tvOrgValue.text = if (userType == "PERSONAL") "미소속" else "설정 안 함"
        binding.tvNameValue.text = userName

        // 💡 저장된 보호자 연동 상태 표시
        if (guardianId!= null) {
            binding.tvGuardianStatus.text = "연동된 보호자: $guardianId"
        } else {
            binding.tvGuardianStatus.text = "보호자 정보가 없습니다"
        }

        // 💡 대망의 보호자 연동 버튼 로직 (팝업창 띄우기)
        binding.btnLinkGuardian.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

            val editText = EditText(this)
            editText.hint = "보호자의 아이디를 입력하세요"

            AlertDialog.Builder(this)
                .setTitle("보호자 연동")
                .setMessage("미리 가입된 보호자 계정의 아이디를 입력해주세요.")
                .setView(editText)
                .setPositiveButton("연동") { _, _ ->
                    val inputId = editText.text.toString()
                    linkGuardianAccount(userId, inputId)
                }
                .setNegativeButton("취소", null)
                .show()
        }

        binding.btnLogout.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            sharedPref.edit().clear().apply()
            Toast.makeText(this, "로그아웃 되었습니다.", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, AuthActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        binding.btnBack.setOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })
    }

    // 💡 DB에서 보호자 아이디가 맞는지 확인하고 내 계정과 묶어주는 함수
    private fun linkGuardianAccount(myId: String, targetGuardianId: String) {
        if (targetGuardianId.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            val guardianUser = database.userDao().getUser(targetGuardianId)
            withContext(Dispatchers.Main) {
                // 1. 해당 아이디가 존재하고, 2. 그 사람의 가입 유형이 "보호자(GUARDIAN)"일 때만 연동 허용
                if (guardianUser!= null && guardianUser.userType == "GUARDIAN") {

                    CoroutineScope(Dispatchers.IO).launch {
                        database.userDao().linkGuardian(myId, targetGuardianId)
                    }

                    // 내 스마트폰 세션에도 연동 완료 정보 저장
                    val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                    sharedPref.edit().putString("GUARDIAN_ID", targetGuardianId).apply()

                    binding.tvGuardianStatus.text = "연동된 보호자: $targetGuardianId"
                    Toast.makeText(this@SettingsActivity, "보호자 연동 성공!", Toast.LENGTH_SHORT).show()

                } else {
                    Toast.makeText(this@SettingsActivity, "해당 아이디의 보호자를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}