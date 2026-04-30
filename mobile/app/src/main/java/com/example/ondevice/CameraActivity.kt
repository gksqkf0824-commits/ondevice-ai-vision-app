package com.example.ondevice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
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
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val textRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    private var targetBusNumber: String? = null
    private var isScanning = false
    private var isOcrProcessing = false
    private var lastSavedTimestamp = 0L

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
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        targetBusNumber = intent.getStringExtra("TARGET_BUS")

        tts = android.speech.tts.TextToSpeech(this) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
            }
        }

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

        binding.btnScan.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            isScanning = true
            binding.btnScan.visibility = View.GONE
            binding.layoutResult.visibility = View.VISIBLE
            speakOut("실시간 환경 탐지를 시작합니다.")
        }

        binding.btnPlayTTS.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            speakOut(binding.tvResultDesc.text.toString())
        }

        val hasCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (hasCam && hasLoc) {
            startCamera()
        } else {
            requestPermissionsLauncher.launch(arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    private fun triggerStrongVibration() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(1000)
        }
    }

    private fun speakOut(text: String) {
        tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "")
    }

    private fun saveToDatabase(eventName: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                val lat = location?.latitude?: 0.0
                val lon = location?.longitude?: 0.0
                val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                val userId = sharedPref.getString("USER_ID", "알 수 없음")?: "알 수 없음"

                CoroutineScope(Dispatchers.IO).launch {
                    AppDatabase.getDatabase(this@CameraActivity).historyDao().insertHistory(
                        History(userName = userId, objectName = eventName, latitude = lat, longitude = lon)
                    )
                }
            }
        }
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
                        if (!isScanning) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        val bitmap = imageProxy.toBitmap()
                        val matrix = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
                        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

                        val mpImage = BitmapImageBuilder(rotatedBitmap).build()
                        val results = objectDetector.detect(mpImage)

                        if (results.detections().isNotEmpty()) {
                            val topDetection = results.detections().first()
                            val category = topDetection.categories().first().categoryName()
                            val score = (topDetection.categories().first().score() * 100).toInt()

                            val bbox = topDetection.boundingBox()
                            val imgWidth = rotatedBitmap.width.toFloat()
                            val centerX = bbox.centerX()
                            val direction = when {
                                centerX < imgWidth * 0.33f -> "10시"
                                centerX > imgWidth * 0.66f -> "2시"
                                else -> "12시"
                            }

                            val scaleX = binding.viewFinder.width.toFloat() / imgWidth
                            val scaleY = binding.viewFinder.height.toFloat() / rotatedBitmap.height.toFloat()
                            val mappedBox = RectF(
                                bbox.left * scaleX,
                                bbox.top * scaleY,
                                bbox.right * scaleX,
                                bbox.bottom * scaleY
                            )

                            if (!targetBusNumber.isNullOrEmpty() && (category.contains("bus", true) || category.contains("car", true))) {
                                if (!isOcrProcessing) {
                                    isOcrProcessing = true

                                    val left = Math.max(0, bbox.left.toInt())
                                    val top = Math.max(0, bbox.top.toInt())
                                    val width = Math.min(rotatedBitmap.width - left, bbox.width().toInt())
                                    val height = Math.min(rotatedBitmap.height - top, bbox.height().toInt())

                                    if (width <= 0 || height <= 0) {
                                        isOcrProcessing = false
                                        imageProxy.close()
                                        return@setAnalyzer
                                    }

                                    val croppedBitmap = Bitmap.createBitmap(rotatedBitmap, left, top, width, height)
                                    val inputImage = InputImage.fromBitmap(croppedBitmap, 0)

                                    textRecognizer.process(inputImage)
                                        .addOnSuccessListener { visionText ->
                                            val recognizedText = visionText.text.replace(Regex("\\s"), "")

                                            if (recognizedText.contains(targetBusNumber!!)) {
                                                val descText = "(TTS) $targetBusNumber 번, 타겟 버스가 감지되었습니다. 승차문은 $direction 방향입니다."

                                                runOnUiThread {
                                                    binding.overlayView.setBoxInfo(mappedBox, Color.MAGENTA, "버스 ${targetBusNumber}번")
                                                    // 💡 지워진 tvBoxText 코드는 완벽히 삭제되었습니다.
                                                    binding.tvResultDesc.text = descText
                                                }

                                                val currentTimestamp = System.currentTimeMillis()
                                                if (currentTimestamp - lastSavedTimestamp >= 5000) {
                                                    lastSavedTimestamp = currentTimestamp
                                                    triggerStrongVibration()
                                                    speakOut(descText)
                                                    saveToDatabase("버스 승차 ($targetBusNumber)")
                                                }
                                            } else {
                                                runOnUiThread {
                                                    binding.overlayView.setBoxInfo(mappedBox, Color.GREEN, "$category ($score%)")
                                                    binding.tvResultDesc.text = "전방 $direction 방향에 버스/차량이 감지되었습니다."
                                                }
                                            }
                                        }
                                        .addOnCompleteListener {
                                            isOcrProcessing = false
                                            imageProxy.close()
                                        }
                                    return@setAnalyzer
                                } else {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }
                            } else {
                                val descText = "전방 $direction 방향에 $category 가 감지되었습니다."
                                runOnUiThread {
                                    binding.overlayView.setBoxInfo(mappedBox, Color.GREEN, "$category ($score%)")
                                    binding.tvResultDesc.text = descText
                                }

                                val currentTimestamp = System.currentTimeMillis()
                                if (currentTimestamp - lastSavedTimestamp >= 5000) {
                                    lastSavedTimestamp = currentTimestamp
                                    speakOut(descText)
                                    saveToDatabase(category)
                                }
                                imageProxy.close()
                            }
                        } else {
                            runOnUiThread {
                                binding.overlayView.clear()
                                binding.tvResultDesc.text = "주변을 탐색 중입니다..."
                            }
                            imageProxy.close()
                        }
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

    // 💡 handleBackAction 에러 없이 100% 정상 작동하도록 수정 완료
    private fun handleBackAction() {
        if (binding.layoutResult.visibility == View.VISIBLE) {
            binding.layoutResult.visibility = View.GONE
            binding.btnScan.visibility = View.VISIBLE
            isScanning = false
            binding.overlayView.clear()
            tts?.stop()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        tts?.stop()
        tts?.shutdown()
        objectDetector.close()
        textRecognizer.close()
    }
}