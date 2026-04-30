package com.example.ondevice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
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
        val orgName = sharedPref.getString("ORG_NAME", "설정 안 함")
        val guardianId = sharedPref.getString("GUARDIAN_ID", null)

        // 내 정보 세팅
        binding.tvOrgValue.text = if (userType == "PERSONAL") "미소속" else orgName
        binding.tvNameValue.text = userName

        // 💡 연동된 보호자가 있으면 DB에서 이름을 찾아 UI를 바꿉니다
        updateGuardianUI(guardianId)

        // 보호자 연동 버튼 로직
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

        binding.switchNotification.setOnCheckedChangeListener { _, _ ->
            window.decorView.rootView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
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

    // 💡 보호자 아이디를 통해 실제 이름을 DB에서 가져와 UI를 업데이트하는 함수
    private fun updateGuardianUI(guardianId: String?) {
        if (guardianId!= null) {
            CoroutineScope(Dispatchers.IO).launch {
                val guardianUser = database.userDao().getUser(guardianId)
                withContext(Dispatchers.Main) {
                    if (guardianUser!= null) {
                        // 연동 버튼 숨기고 이름/번호 표시
                        binding.layoutUnlinkedGuardian.visibility = View.GONE
                        binding.layoutLinkedGuardian.visibility = View.VISIBLE
                        binding.tvGuardianNameValue.text = guardianUser.name
                        // 전화번호는 현재 DB에 없으므로 피그마 디자인처럼 고정값 표시
                        binding.tvGuardianPhoneValue.text = "080-098-1004"
                    }
                }
            }
        } else {
            // 보호자가 없으면 버튼 표시
            binding.layoutUnlinkedGuardian.visibility = View.VISIBLE
            binding.layoutLinkedGuardian.visibility = View.GONE
        }
    }

    private fun linkGuardianAccount(myId: String, targetGuardianId: String) {
        if (targetGuardianId.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            val guardianUser = database.userDao().getUser(targetGuardianId)
            withContext(Dispatchers.Main) {
                if (guardianUser!= null && guardianUser.userType == "GUARDIAN") {

                    CoroutineScope(Dispatchers.IO).launch {
                        database.userDao().linkGuardian(myId, targetGuardianId)
                    }

                    val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                    sharedPref.edit().putString("GUARDIAN_ID", targetGuardianId).apply()

                    // 💡 연동 성공 즉시 화면에 보호자 이름이 뜨도록 업데이트!
                    updateGuardianUI(targetGuardianId)
                    Toast.makeText(this@SettingsActivity, "보호자 연동 성공!", Toast.LENGTH_SHORT).show()

                } else {
                    Toast.makeText(this@SettingsActivity, "해당 아이디의 보호자를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}