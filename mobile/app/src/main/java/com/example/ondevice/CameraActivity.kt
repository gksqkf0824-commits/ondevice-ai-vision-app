package com.example.ondevice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.ondevice.databinding.ActivityCameraBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private lateinit var cameraExecutor: ExecutorService
    private var tts: android.speech.tts.TextToSpeech? = null

    private lateinit var objectDetector: ObjectDetector
    private var latestBitmap: Bitmap? = null

    // 💡 GPS 위치 가져오기 도구
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var targetBusNumber: String? = null

    // 💡 오류 원인을 원천 차단한 완전히 새로운 방식의 코드입니다.
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // 요청한 모든 권한의 결과값들(values)이 전부(all) true인지 한 번에 확인합니다.
        val isAllGranted = permissions.values.all { it == true }

        if (isAllGranted) {
            startCamera() // 전부 허용되었을 때만 카메라 실행
        } else {
            Toast.makeText(this, "카메라 및 위치 권한이 필요합니다.", Toast.LENGTH_LONG).show()
            finish()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 위치 서비스 클라이언트 초기화
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // 이전 화면(BusInputActivity)에서 넘겨받은 타겟 버스 번호 저장
        targetBusNumber = intent.getStringExtra("TARGET_BUS")

        tts = android.speech.tts.TextToSpeech(this) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
            }
        }

        // AI 모델 설정
        val baseOptions = BaseOptions.builder().setModelAssetPath("efficientdet.tflite").build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setMaxResults(1)
            .build()
        objectDetector = ObjectDetector.createFromOptions(this, options)

        binding.btnBack.setOnClickListener { handleBackAction() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBackAction() }
        })

        // 스캔 버튼 클릭 로직
        binding.btnScan.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

            latestBitmap?.let { bitmap ->
                val mpImage = BitmapImageBuilder(bitmap).build()
                val results = objectDetector.detect(mpImage)

                if (results.detections().isNotEmpty()) {
                    val topDetection = results.detections().first()
                    val category = topDetection.categories().first().categoryName()
                    val score = (topDetection.categories().first().score() * 100).toInt()

                    var descText = "전방에 $category 가 감지되었습니다."

                    // 타겟 버스 안내 로직
                    if (!targetBusNumber.isNullOrEmpty()) {
                        if (category.contains("bus", ignoreCase = true) || category.contains("car", ignoreCase = true)) {
                            descText = "(TTS) $targetBusNumber 번, 타겟 버스가 감지되었습니다. 승차문은 12시 방향입니다."
                        }
                    }

                    binding.tvBoxText.text = "$category\n($score%)"
                    binding.tvResultDesc.text = descText

                    // 💡 DB 저장 부분: description을 빼고 위도/경도(GPS)로 완전히 교체
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                            val lat = location?.latitude?: 0.0
                            val lon = location?.longitude?: 0.0

                            val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                            val userId = sharedPref.getString("USER_ID", "알 수 없음")?: "알 수 없음"

                            CoroutineScope(Dispatchers.IO).launch {
                                val db = AppDatabase.getDatabase(this@CameraActivity)
                                db.historyDao().insertHistory(
                                    History(userName = userId, objectName = category, latitude = lat, longitude = lon)
                                )
                            }
                        }
                    }

                    binding.btnScan.visibility = View.GONE
                    binding.layoutResult.visibility = View.VISIBLE
                    speakOut(descText)

                } else {
                    Toast.makeText(this, "인식된 객체가 없습니다. 다시 시도해주세요.", Toast.LENGTH_SHORT).show()
                }
            }?: run {
                Toast.makeText(this, "카메라 대기 중입니다.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnPlayTTS.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            speakOut(binding.tvResultDesc.text.toString())
        }

        // 앱 켤 때 권한 확인
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionsLauncher.launch(arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    private fun handleBackAction() {
        if (binding.layoutResult.visibility == View.VISIBLE) {
            binding.layoutResult.visibility = View.GONE
            binding.btnScan.visibility = View.VISIBLE
            tts?.stop()
        } else {
            finish()
        }
    }

    private fun speakOut(text: String) {
        tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "")
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        val bitmap = imageProxy.toBitmap()
                        val matrix = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
                        latestBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        imageProxy.close()
                    }
                }
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch(exc: Exception) {
                Toast.makeText(this, "카메라 초기화 실패", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        tts?.stop()
        tts?.shutdown()
        objectDetector.close()
    }
}