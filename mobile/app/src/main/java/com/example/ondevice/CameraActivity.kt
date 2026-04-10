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
import androidx.core.content.ContextCompat
import com.example.ondevice.databinding.ActivityCameraBinding
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

    // AI 관련 변수들
    private lateinit var objectDetector: ObjectDetector
    private var latestBitmap: Bitmap? = null // 카메라가 보고 있는 가장 최근 장면을 저장할 변수

    // 카메라 권한 요청
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) startCamera() else finish()
    }

    // 갤러리 열기 도구
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri!= null) {
            binding.viewFinder.visibility = View.GONE
            binding.ivSelectedImage.visibility = View.VISIBLE
            binding.ivSelectedImage.setImageURI(uri)
            speakOut("이미지가 첨부되었습니다. 화면 하단의 스캔 버튼을 눌러주세요.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 1. TTS 초기화
        tts = android.speech.tts.TextToSpeech(this) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
            }
        }

        // 2. 💡 MediaPipe AI 객체 인식기(ObjectDetector) 초기화
        val baseOptions = BaseOptions.builder().setModelAssetPath("efficientdet.tflite").build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setMaxResults(1) // 가장 확률이 높은 물체 1개만 찾기
            .build()
        objectDetector = ObjectDetector.createFromOptions(this, options)

        // 버튼 로직들
        binding.btnBack.setOnClickListener { handleBackAction() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBackAction() }
        })

        binding.btnAttachImage.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            pickMedia.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        if (intent.getBooleanExtra("OPEN_GALLERY", false)) {
            pickMedia.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        // 3. 💡 스캔 버튼 클릭 시 진짜 AI 추론 실행
        binding.btnScan.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

            latestBitmap?.let { bitmap ->
                // 안드로이드 이미지를 MediaPipe용 이미지로 변환
                val mpImage = BitmapImageBuilder(bitmap).build()
                // AI 모델에게 사진을 주고 무엇인지 맞추라고 명령
                val results = objectDetector.detect(mpImage)

                // 결과가 있다면 (물체를 하나라도 찾았다면)
                if (results.detections().isNotEmpty()) {
                    // 💡.first()를 추가하여 여러 물체 중 첫 번째(1순위) 물체를 꺼냅니다.
                    val topDetection = results.detections().first()

                    // 그 물체의 정답 후보들 중 첫 번째(1순위) 이름과 확률을 가져옵니다.
                    val category = topDetection.categories().first().categoryName()
                    val score = (topDetection.categories().first().score() * 100).toInt()

                    val resultText = "${category}\n($score%)"
                    val descText = "인식된 대상은 '$category' 입니다. AI의 확신도는 $score% 입니다."

                    // 화면 업데이트
                    binding.tvBoxText.text = resultText
                    binding.tvResultDesc.text = descText

                    // Room DB에 이 기록을 영구 저장
                    val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                    val userId = sharedPref.getString("USER_ID", "알 수 없음")?: "알 수 없음"

                    CoroutineScope(Dispatchers.IO).launch {
                        val db = AppDatabase.getDatabase(this@CameraActivity)
                        db.historyDao().insertHistory(History(userName = userId, objectName = category, description = descText))
                    }

                    // 결과창 띄우고 음성으로 읽어주기
                    binding.btnScan.visibility = View.GONE
                    binding.btnAttachImage.visibility = View.GONE
                    binding.layoutResult.visibility = View.VISIBLE

                    speakOut("$category 가 인식되었습니다. 상세 정보를 들으려면 화면 아래의 음성 안내 듣기 버튼을 누르세요.")
                } else {
                    Toast.makeText(this, "인식된 객체가 없습니다. 다른 각도로 시도해주세요.", Toast.LENGTH_SHORT).show()
                    speakOut("인식된 객체가 없습니다. 다른 각도로 시도해주세요.")
                }
            }?: run {
                Toast.makeText(this, "카메라를 준비 중입니다.", Toast.LENGTH_SHORT).show()
            }
        }

        // 음성 안내 버튼
        binding.btnPlayTTS.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            speakOut(binding.tvResultDesc.text.toString())
        }

        // 권한 확인
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun handleBackAction() {
        if (binding.layoutResult.visibility == View.VISIBLE) {
            binding.layoutResult.visibility = View.GONE
            binding.btnScan.visibility = View.VISIBLE
            binding.btnAttachImage.visibility = View.VISIBLE
            tts?.stop()
        } else if (binding.ivSelectedImage.visibility == View.VISIBLE) {
            binding.ivSelectedImage.visibility = View.GONE
            binding.viewFinder.visibility = View.VISIBLE
            speakOut("카메라 모드로 돌아왔습니다.")
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
                        // 💡 AI가 분석하기 좋게 카메라 프레임을 비트맵(사진)으로 실시간 변환해서 저장해 둡니다.
                        val bitmap = imageProxy.toBitmap()
                        val matrix = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
                        latestBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

                        imageProxy.close() // 메모리 누수 방지
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
        objectDetector.close() // AI 모델 메모리 해제
    }
}