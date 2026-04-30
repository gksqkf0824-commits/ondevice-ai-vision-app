package com.example.ondevice

import android.content.Context
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

        binding.btnToggleGraph.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (binding.recyclerView.visibility == View.VISIBLE) {
                binding.recyclerView.visibility = View.GONE
                binding.layoutSearch.visibility = View.GONE
                binding.layoutGraph.visibility = View.VISIBLE
                binding.tvTitle.text = "위험 객체 위치 로그"
                binding.btnToggleGraph.text = "이용자 기록 확인으로 돌아가기"

                setupBarChart()
            } else {
                binding.recyclerView.visibility = View.VISIBLE
                binding.layoutSearch.visibility = View.VISIBLE
                binding.layoutGraph.visibility = View.GONE
                binding.tvTitle.text = "이용자 기록 확인"
                binding.btnToggleGraph.text = "위험 객체 위치 로그"
            }
        }

        loadHistoryData("")
    }

    private suspend fun fetchFilteredHistory(searchQuery: String = ""): List<History> {
        val dao = database.historyDao()
        val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val userId = sharedPref.getString("USER_ID", "")?: ""
        val userType = sharedPref.getString("USER_TYPE", "PERSONAL")

        return if (userType == "GUARDIAN") {
            if (searchQuery.isEmpty()) dao.getHistoryForGuardian(userId)
            else dao.searchHistoryForGuardian(userId, searchQuery)
        } else {
            if (searchQuery.isEmpty()) dao.getHistoryForUser(userId)
            else dao.searchHistoryForUser(userId, searchQuery)
        }
    }

    // 💡 2. 위치별로 데이터를 묶어서 여러 개의 그래프를 찍어내는 핵심 로직
    private fun setupBarChart() {
        CoroutineScope(Dispatchers.IO).launch {
            val historyList = fetchFilteredHistory("")

            // 💡 위도와 경도를 소수점 둘째 자리(약 1km 반경) 단위로 문자열로 만들어 그룹화합니다.
            val groupedByLocation = historyList.groupBy {
                val latStr = String.format(Locale.US, "%.2f", it.latitude)
                val lonStr = String.format(Locale.US, "%.2f", it.longitude)
                "$latStr° N, $lonStr° E ±0.01"
            }

            withContext(Dispatchers.Main) {
                binding.chartContainer.removeAllViews() // 기존 그래프들 초기화

                var groupIndex = 1
                for ((locationGroup, items) in groupedByLocation) {
                    // 💡 그룹 갯수만큼 item_chart.xml을 복사해서 화면에 추가합니다.
                    val chartView = layoutInflater.inflate(R.layout.item_chart, binding.chartContainer, false)
                    val tvLocationTitle = chartView.findViewById<TextView>(R.id.tvLocationTitle)
                    val barChart = chartView.findViewById<com.github.mikephil.charting.charts.BarChart>(R.id.barChart)

                    tvLocationTitle.text = "$groupIndex. 위치 범위: $locationGroup"

                    // 해당 위치 내에서 객체별 인식 횟수 카운트
                    val objectCounts = items.groupingBy { it.objectName }.eachCount()
                    val entries = ArrayList<BarEntry>()
                    val labels = ArrayList<String>()

                    var xIndex = 0f
                    for ((objName, count) in objectCounts) {
                        entries.add(BarEntry(xIndex, count.toFloat()))
                        labels.add(objName)
                        xIndex += 1f
                    }

                    val dataSet = BarDataSet(entries, "인식 횟수")
                    dataSet.color = android.graphics.Color.BLACK
                    dataSet.valueTextColor = android.graphics.Color.BLACK
                    dataSet.valueTextSize = 14f

                    val barData = BarData(dataSet)
                    barData.barWidth = 0.5f

                    barChart.data = barData
                    barChart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
                    barChart.xAxis.position = XAxis.XAxisPosition.BOTTOM
                    barChart.xAxis.granularity = 1f
                    barChart.xAxis.setDrawGridLines(false)

                    barChart.axisLeft.granularity = 1f
                    barChart.axisLeft.axisMinimum = 0f // y축이 항상 0부터 시작하도록 설정
                    barChart.axisRight.isEnabled = false
                    barChart.description.isEnabled = false
                    barChart.legend.isEnabled = false // 피그마 디자인 반영: 범례 숨김

                    barChart.invalidate()
                    barChart.animateY(1000)

                    binding.chartContainer.addView(chartView)
                    groupIndex++
                }
            }
        }
    }

    private fun loadHistoryData(searchQuery: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val historyList = fetchFilteredHistory(searchQuery)

            // 테스트를 위해 DB에 내 기록이 전혀 없을 때만 가짜 데이터를 위치별로 나누어 집어넣습니다.
            if (historyList.isEmpty() && searchQuery.isEmpty()) {
                val dao = database.historyDao()
                val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                val userId = sharedPref.getString("USER_ID", "사용자1")?: "사용자1"

                // 💡 위치가 서로 다른 가짜 데이터 생성 (소수점 둘째 자리가 다름)
                dao.insertHistory(History(userName = userId, objectName = "전동 킥보드", latitude = 37.56, longitude = 126.97))
                dao.insertHistory(History(userName = userId, objectName = "자동차", latitude = 37.56, longitude = 126.97))
                dao.insertHistory(History(userName = userId, objectName = "자전거", latitude = 37.57, longitude = 126.98))
                dao.insertHistory(History(userName = userId, objectName = "손수레", latitude = 37.57, longitude = 126.98))
                dao.insertHistory(History(userName = userId, objectName = "전동 킥보드", latitude = 37.59, longitude = 126.97))

                // 데이터 넣고 다시 불러오기
                val newList = fetchFilteredHistory("")
                withContext(Dispatchers.Main) {
                    binding.recyclerView.adapter = HistoryAdapter(newList)
                }
            } else {
                withContext(Dispatchers.Main) {
                    binding.recyclerView.adapter = HistoryAdapter(historyList)
                }
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
        val locationText = "시간: $formattedTime, 위치: ${String.format(Locale.US, "%.3f", item.latitude)}° N, ${String.format(Locale.US, "%.3f", item.longitude)}° E"

        holder.tvDescription.text = locationText
        holder.itemRoot.contentDescription = "$titleText. $locationText"

        holder.btnTts.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            Toast.makeText(it.context, "${item.objectName} 시간 및 위치 안내 시작", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount(): Int = historyList.size
}