package com.example.ondevice

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ondevice.databinding.ActivityHistoryBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var database: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(this)

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

        binding.recyclerView.layoutManager = LinearLayoutManager(this)

        // 💡 실시간 검색 기능 설정
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString()
                if (query.isEmpty()) {
                    loadHistoryData("") // 검색어가 없으면 전체 표시
                } else {
                    loadHistoryData(query) // 검색어가 있으면 DB에서 필터링
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // 초기 데이터 로드 (전체 표시)
        loadHistoryData("")
    }

    private fun loadHistoryData(searchQuery: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = database.historyDao()

            // (테스트용) DB가 비어있으면 가짜 데이터를 넣습니다.
            if (dao.getAllHistory().isEmpty()) {
                dao.insertHistory(History(userName = "기관 이용자1", objectName = "서울 우유", description = "인식한 대상은 '서울 우유' 입니다. 소비 기한은 3월 27일 04:00 까지 입니다."))
                dao.insertHistory(History(userName = "기관 이용자2", objectName = "북해도 우유 푸딩", description = "인식한 대상은 '북해도 우유 푸딩' 입니다. 소비 기한은 3월 27일 04:00 까지 입니다."))
                dao.insertHistory(History(userName = "기관 이용자1", objectName = "나메라카 푸딩", description = "인식한 대상은 '나메라카 푸딩' 입니다. 영양 정보에 대해서는..."))
                dao.insertHistory(History(userName = "사용자1", objectName = "소화가 잘 되는 우유", description = "인식한 대상은 '소화가 잘되는 우유' 입니다. 소비 기한은 3월 27일 04:00 까지 입니다."))
            }

            // 검색어가 있으면 searchHistory, 없으면 getAllHistory 실행
            val historyList = if (searchQuery.isEmpty()) {
                dao.getAllHistory()
            } else {
                dao.searchHistory(searchQuery)
            }

            withContext(Dispatchers.Main) {
                binding.recyclerView.adapter = HistoryAdapter(historyList)
            }
        }
    }
}

class HistoryAdapter(private val historyList: List<History>) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val itemRoot: View = view.findViewById(R.id.itemRoot)
        val tvObjectName: TextView = view.findViewById(R.id.tvObjectName)
        val tvDescription: TextView = view.findViewById(R.id.tvDescription)
        val btnTts: ImageView = view.findViewById(R.id.btnTts)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = historyList[position]

        // 피그마 디자인 반영: "사용자명 | 사물이름" 형태로 표시
        val titleText = "${item.userName} | ${item.objectName}"
        holder.tvObjectName.text = titleText
        holder.tvDescription.text = item.description

        // 💡 [접근성 그룹화 핵심] 리스트 한 칸을 포커스 했을 때, 제목과 내용을 한 문장처럼 이어서 자연스럽게 읽어주도록 세팅
        holder.itemRoot.contentDescription = "$titleText. ${item.description}"

        holder.btnTts.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            Toast.makeText(it.context, "${item.objectName} 음성 안내 시작", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount(): Int = historyList.size
}