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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.ondevice.databinding.ActivityCameraBinding
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private lateinit var cameraExecutor: ExecutorService
    private var tts: android.speech.tts.TextToSpeech? = null

    private lateinit var tflite: Interpreter
    private var latestBitmap: Bitmap? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startCamera() else finish()
    }

    private val pickMedia = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
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

        tts = android.speech.tts.TextToSpeech(this) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
            }
        }

        // YOLO TFLite 모델 로딩
        tflite = Interpreter(loadModelFile("yolov11n_bus_door.tflite"))

        val inputShape = tflite.getInputTensor(0).shape().contentToString()
        val outputShape = tflite.getOutputTensor(0).shape().contentToString()

        android.util.Log.d("YOLO_SHAPE", "inputShape=$inputShape")
        android.util.Log.d("YOLO_SHAPE", "outputShape=$outputShape")

        binding.btnBack.setOnClickListener {
            handleBackAction()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackAction()
            }
        })

        binding.btnAttachImage.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            pickMedia.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        if (intent.getBooleanExtra("OPEN_GALLERY", false)) {
            pickMedia.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        binding.btnScan.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

            latestBitmap?.let { bitmap ->
                val start = System.nanoTime()

                val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 640, 640, true)

                // TODO 1: resizedBitmap을 YOLO 입력 Tensor로 변환
                // TODO 2: tflite.run(input, output)
                // TODO 3: output 후처리해서 bus / door bbox 추출

                val end = System.nanoTime()
                val elapsedMs = (end - start) / 1_000_000.0
                val fps = 1000.0 / elapsedMs

                binding.tvBoxText.text = "YOLO 모델 연결 준비 완료"
                binding.tvResultDesc.text =
                    "처리시간: ${"%.2f".format(elapsedMs)}ms / FPS: ${"%.2f".format(fps)}"

                binding.btnScan.visibility = View.GONE
                binding.btnAttachImage.visibility = View.GONE
                binding.layoutResult.visibility = View.VISIBLE

                speakOut("YOLO 모델 연결 준비가 완료되었습니다.")

            } ?: run {
                Toast.makeText(this, "카메라를 준비 중입니다.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnPlayTTS.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            speakOut(binding.tvResultDesc.text.toString())
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
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
                        val bitmap = imageProxy.toBitmap()
                        val matrix = Matrix().apply {
                            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                        }

                        latestBitmap = Bitmap.createBitmap(
                            bitmap,
                            0,
                            0,
                            bitmap.width,
                            bitmap.height,
                            matrix,
                            true
                        )

                        imageProxy.close()
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )
            } catch (exc: Exception) {
                Toast.makeText(this, "카메라 초기화 실패", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val fileDescriptor = assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel

        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        tts?.stop()
        tts?.shutdown()
        tflite.close()
    }
}