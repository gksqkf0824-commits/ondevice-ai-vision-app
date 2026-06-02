package com.example.ondevice

import android.graphics.Color
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.ondevice.databinding.ActivitySignupBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.ondevice.network.RetrofitClient

class SignupActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySignupBinding
    private lateinit var database: AppDatabase
    private var selectedType = "PERSONAL" // 가입 유형: PERSONAL(개인), ORG(기관), GUARDIAN(보호자)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(this)

        // 💡 탭 클릭 리스너 연결
        binding.tabPersonal.setOnClickListener { selectTab("PERSONAL") }
        binding.tabOrg.setOnClickListener { selectTab("ORG") }
        binding.tabGuardian.setOnClickListener { selectTab("GUARDIAN") }

        // 회원가입 확인 버튼
        binding.btnSignup.setOnClickListener {
            val user = User(
                username = binding.etId.text.toString(), // 필드명 변경
                password = binding.etPassword.text.toString(),
                name = binding.etName.text.toString(),
                role = selectedType, // userType 대신 role 사용
                orgName = binding.etOrgName.text.toString()
            )

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val response = when (selectedType) { // 유형별 API 호출
                        "PERSONAL" -> RetrofitClient.instance.signupPersonal(user)
                        "ORG" -> RetrofitClient.instance.signupCompany(user)
                        "GUARDIAN" -> RetrofitClient.instance.signupGuardian(user)
                        else -> null
                    }

                    withContext(Dispatchers.Main) {
                        if (response?.isSuccessful == true) {
                            Toast.makeText(this@SignupActivity, "회원가입 성공!", Toast.LENGTH_SHORT).show()
                            finish()
                        } else {
                            // 백엔드에서 보내준 실제 에러 메시지
                            val errorBody = response?.errorBody()?.string() ?: "알 수 없는 에러"
                            Toast.makeText(this@SignupActivity, "가입 실패: $errorBody", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        // "네트워크 오류" 대신 실제 에러 메시지
                        Toast.makeText(this@SignupActivity, "에러: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        e.printStackTrace() // Logcat에서 상세 내용을 보기 위함
                    }
                }
            }
        }

        binding.btnCancel.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            finish()
        }
    }

    private fun selectTab(type: String) {
        selectedType = type

        val activeTabBg = ContextCompat.getDrawable(this, R.drawable.bg_tab_active_dark)
        val activeTextColor = Color.parseColor("#111111")
        val inactiveTextColor = Color.parseColor("#59000000")

        binding.tabPersonal.background = null
        binding.tabPersonal.setTextColor(inactiveTextColor)
        binding.tabOrg.background = null
        binding.tabOrg.setTextColor(inactiveTextColor)
        binding.tabGuardian.background = null
        binding.tabGuardian.setTextColor(inactiveTextColor)

        when (type) {
            "PERSONAL" -> {
                binding.tabPersonal.background = activeTabBg
                binding.tabPersonal.setTextColor(activeTextColor)
                binding.etOrgName.visibility = View.GONE
                binding.etName.visibility = View.GONE
            }
            "ORG" -> {
                binding.tabOrg.background = activeTabBg
                binding.tabOrg.setTextColor(activeTextColor)
                binding.etOrgName.visibility = View.VISIBLE
                binding.etName.visibility = View.VISIBLE
                binding.etName.hint = "사용자명"
            }
            "GUARDIAN" -> {
                binding.tabGuardian.background = activeTabBg
                binding.tabGuardian.setTextColor(activeTextColor)
                binding.etOrgName.visibility = View.VISIBLE
                binding.etName.visibility = View.VISIBLE
                binding.etName.hint = "보호자명"
            }
        }
    }
}