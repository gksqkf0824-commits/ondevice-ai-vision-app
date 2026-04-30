package com.example.ondevice

import android.graphics.Color
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.ondevice.databinding.ActivitySignupBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

            val orgName = binding.etOrgName.text.toString()
            val name = binding.etName.text.toString()
            val id = binding.etId.text.toString()
            val pw = binding.etPassword.text.toString()
            val pwConfirm = binding.etPasswordConfirm.text.toString()

            // 공통 필수값 체크
            if (id.isEmpty() || pw.isEmpty() || pwConfirm.isEmpty()) {
            Toast.makeText(this, "필수 항목을 모두 입력해주세요.", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }

            // 기관이나 보호자일 때 추가 필수값 체크
            if (selectedType!= "PERSONAL" && (orgName.isEmpty() || name.isEmpty())) {
            Toast.makeText(this, "기관명과 이름을 입력해주세요.", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }

            if (pw!= pwConfirm) {
                Toast.makeText(this, "비밀번호가 일치하지 않습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            CoroutineScope(Dispatchers.IO).launch {
                val existingUser = database.userDao().checkIdExist(id)
                if (existingUser!= null) {
                    withContext(Dispatchers.Main) { Toast.makeText(this@SignupActivity, "이미 존재하는 아이디입니다.", Toast.LENGTH_SHORT).show() }
                } else {
                    // 💡 수정된 부분: 이름에 기관명을 합치던 예전 꼼수를 지우고,
                    // DB의 orgName 칸에 기관명을 정식으로 따로 분리해서 저장합니다!
                    val finalOrgName = if (selectedType == "PERSONAL") null else orgName
                    val finalName = if (selectedType == "PERSONAL") "개인사용자" else name

                    database.userDao().insertUser(
                        User(
                            userId = id,
                            name = finalName,
                            password = pw,
                            userType = selectedType,
                            orgName = finalOrgName
                        )
                    )

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SignupActivity, "회원가입 성공!", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
        }

        // 취소 버튼
        binding.btnCancel.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            finish()
        }
    }

    // 💡 탭 디자인 변경 및 입력창 숨기기/보이기 조작 함수
    private fun selectTab(type: String) {
        selectedType = type

        // 1. 모든 탭 디자인 초기화 (투명 배경, 검은 글씨)
        binding.tabPersonal.setBackgroundColor(Color.TRANSPARENT)
        binding.tabPersonal.setTextColor(Color.BLACK)
        binding.tabOrg.setBackgroundColor(Color.TRANSPARENT)
        binding.tabOrg.setTextColor(Color.BLACK)
        binding.tabGuardian.setBackgroundColor(Color.TRANSPARENT)
        binding.tabGuardian.setTextColor(Color.BLACK)

        // 2. 선택된 탭을 까맣게 칠하고, 노란 글씨로 변경. 그리고 입력창 보이기 설정
        when (type) {
            "PERSONAL" -> {
                binding.tabPersonal.setBackgroundColor(Color.BLACK)
                binding.tabPersonal.setTextColor(Color.parseColor("#FFEB3B"))
                binding.etOrgName.visibility = View.GONE
                binding.etName.visibility = View.GONE
            }
            "ORG" -> {
                binding.tabOrg.setBackgroundColor(Color.BLACK)
                binding.tabOrg.setTextColor(Color.parseColor("#FFEB3B"))
                binding.etOrgName.visibility = View.VISIBLE
                binding.etName.visibility = View.VISIBLE
                binding.etName.hint = "사용자명"
            }
            "GUARDIAN" -> {
                binding.tabGuardian.setBackgroundColor(Color.BLACK)
                binding.tabGuardian.setTextColor(Color.parseColor("#FFEB3B"))
                binding.etOrgName.visibility = View.VISIBLE
                binding.etName.visibility = View.VISIBLE
                binding.etName.hint = "보호자명"
            }
        }
    }
}