package com.example.ondevice // 💡 본인의 실제 패키지명으로 꼭 변경하세요!

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
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                loadHistoryData(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // 💡 하단 [리스트 <-> 그래프] 전환 버튼 로직
        binding.btnToggleGraph.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (binding.recyclerView.visibility == View.VISIBLE) {
                // 리스트 끄고 그래프 켜기
                binding.recyclerView.visibility = View.GONE
                binding.layoutSearch.visibility = View.GONE
                binding.layoutGraph.visibility = View.VISIBLE
                binding.tvTitle.text = "위험 객체 위치 로그"
                binding.btnToggleGraph.text = "이용자 기록 확인으로 돌아가기"

                setupBarChart() // 💡 DB 읽어서 차트 그리기 실행!
            } else {
                // 그래프 끄고 리스트 켜기
                binding.recyclerView.visibility = View.VISIBLE
                binding.layoutSearch.visibility = View.VISIBLE
                binding.layoutGraph.visibility = View.GONE
                binding.tvTitle.text = "이용자 기록 확인"
                binding.btnToggleGraph.text = "위험 객체 위치 로그"
            }
        }

        loadHistoryData("")
    }

    // 💡 DB에서 데이터를 가져와서 통계를 내고 차트를 그리는 함수
    private fun setupBarChart() {
        CoroutineScope(Dispatchers.IO).launch {
            val historyList = database.historyDao().getAllHistory()

            // 객체 이름(예: 전동 킥보드, 버스 등)별로 그룹을 묶고 갯수를 셉니다.
            val groupedData = historyList.groupBy { it.objectName }

            val entries = ArrayList<BarEntry>()
            val labels = ArrayList<String>()

            var index = 0f
            for ((name, items) in groupedData) {
                entries.add(BarEntry(index, items.size.toFloat()))
                labels.add(name)
                index += 1f
            }

            val dataSet = BarDataSet(entries, "인식 횟수")
            dataSet.color = android.graphics.Color.BLACK
            dataSet.valueTextColor = android.graphics.Color.BLACK
            dataSet.valueTextSize = 14f

            val barData = BarData(dataSet)
            barData.barWidth = 0.5f

            withContext(Dispatchers.Main) {
                binding.barChart.data = barData
                // X축 글자를 사물 이름으로 설정
                binding.barChart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
                binding.barChart.xAxis.position = XAxis.XAxisPosition.BOTTOM
                binding.barChart.xAxis.granularity = 1f
                binding.barChart.xAxis.setDrawGridLines(false)

                // 불필요한 차트 요소 숨기기
                binding.barChart.axisLeft.granularity = 1f
                binding.barChart.axisRight.isEnabled = false
                binding.barChart.description.isEnabled = false

                // 차트 새로고침 및 예쁜 솟아오르는 애니메이션 적용
                binding.barChart.invalidate()
                binding.barChart.animateY(1000)
            }
        }
    }

    private fun loadHistoryData(searchQuery: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = database.historyDao()

            // 초기 가짜 데이터 삽입 (테스트용)
            if (dao.getAllHistory().isEmpty()) {
                dao.insertHistory(History(userName = "사용자1", objectName = "전동 킥보드", latitude = 37.566, longitude = 126.970))
                dao.insertHistory(History(userName = "사용자3", objectName = "자동차", latitude = 37.566, longitude = 126.978))
                dao.insertHistory(History(userName = "사용자2", objectName = "자전거", latitude = 37.567, longitude = 126.971))
                dao.insertHistory(History(userName = "사용자2", objectName = "손수레", latitude = 37.568, longitude = 126.975))
            }

            val historyList = if (searchQuery.isEmpty()) dao.getAllHistory() else dao.searchHistory(searchQuery)

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

        val titleText = "${item.userName} | ${item.objectName}"
        holder.tvObjectName.text = titleText

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val formattedTime = timeFormat.format(Date(item.timestamp))
        val locationText = "시간: $formattedTime, 위치: ${String.format("%.3f", item.latitude)}° N, ${String.format("%.3f", item.longitude)}° E"

        holder.tvDescription.text = locationText
        holder.itemRoot.contentDescription = "$titleText. $locationText"

        holder.btnTts.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            Toast.makeText(it.context, "${item.objectName} 시간 및 위치 안내 시작", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount(): Int = historyList.size
}