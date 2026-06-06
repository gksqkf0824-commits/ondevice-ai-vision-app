package com.example.ondevice

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.ondevice.databinding.ActivityCameraBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
// [최적화 추가] GPU Delegate import
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class CameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraActivity"
        private const val METRIC_TAG = "CameraMetrics"

        // 알고리즘 1:
        // 사용자 화면 기준 bus bounding box 면적이 전체 화면의 40% 이상일 때만 OCR 수행
        private const val BUS_OCR_AREA_RATIO_THRESHOLD = 0.12f
        private const val OCR_MIN_INTERVAL_MS = 1200L
        private const val OCR_NO_TARGET_MIN_INTERVAL_MS = 3000L
        private const val OCR_CROP_PADDING_RATIO = 0.12f
        private const val OCR_MIN_CROP_LONG_SIDE = 720
        private const val OCR_MAX_UPSCALE = 3.0f
        private const val OCR_ROUTE_CONFIRM_WINDOW_MS = 4500L
        private const val OCR_ROUTE_CONFIRM_MIN_HITS = 2

        private const val MODEL_ASSET_NAME = "improved_model_320_full_int8.tflite"
        private const val ANALYSIS_TARGET_WIDTH = 320
        private const val ANALYSIS_TARGET_HEIGHT = 320
        private const val BENCHMARK_FRAME_WINDOW = 10
        private const val PERF_FRAME_LOG_INTERVAL = 10
        private const val BENCHMARK_TARGET_FPS = 15.0
        private const val BENCHMARK_TARGET_FRAME_MS = 100.0
        private const val BENCHMARK_TARGET_INFERENCE_MS = 66.0
        private const val BENCHMARK_TARGET_OCR_MS = 1000.0
        // OCR 진행 중에도 완전히 멈추지 않고 저빈도로만 YOLO를 돌리기 위한 최소 간격 (ms)
        private const val OCR_MODE_ANALYSIS_INTERVAL_MS = 80L

        // --- 버스문 오탐 감소용 휴리스틱(코드 레벨 보완) ---
        // bus bbox 안에 충분히 포함되는 door만 유지 (intersection / doorArea)
        private const val DOOR_MIN_IOA_WITH_BUS = 0.35f
        // bus bbox 대비 door bbox 면적 비율 범위
        private const val DOOR_MIN_AREA_RATIO_TO_BUS = 0.003f
        private const val DOOR_MAX_AREA_RATIO_TO_BUS = 0.40f
        // door bbox 종횡비 범위 (width/height)
        private const val DOOR_MIN_ASPECT_RATIO = 0.15f
        private const val DOOR_MAX_ASPECT_RATIO = 3.50f
        private const val BUS_MODEL_SCORE_THRESHOLD = 0.60f
        private const val BUS_MIN_AREA_RATIO = 0.04f
        private const val BUS_MIN_ASPECT_RATIO = 0.70f
        private const val BUS_MAX_ASPECT_RATIO = 4.50f
        private const val DOOR_MODEL_SCORE_THRESHOLD = 0.25f
        private const val DOOR_MIN_SCORE = 0.30f

        // 앞문 선택 스무딩(짧은 시간 내 선택 유지)
        private const val FRONT_DOOR_SMOOTHING_WINDOW_MS = 1400L
        private const val FRONT_DOOR_SMOOTHING_MIN_IOU = 0.30f
    }

    private lateinit var binding: ActivityCameraBinding
    private lateinit var cameraExecutor: ExecutorService
    private var tts: android.speech.tts.TextToSpeech? = null

    private lateinit var interpreter: Interpreter
    private lateinit var labels: List<String>
    private var gpuDelegate: GpuDelegate? = null
    private val byteTracker = ByteTracker()
    private var inferenceBackend = "unknown"
    private var inputDataType = DataType.FLOAT32
    private var outputDataType = DataType.FLOAT32
    private var inputScale = 1f
    private var inputZeroPoint = 0
    private var outputScale = 1f
    private var outputZeroPoint = 0
    private var useFastInt8InputQuantization = false

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val textRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    private data class RouteCandidate(
        val number: String,
        val box: RectF,
        val score: Float
    )

    private var targetBusNumber: String? = null
    private var isScanning = false
    private var isTtsReady = false
    @Volatile private var isOcrProcessing = false
    private var lastSavedTimestamp = 0L
    private var lastOcrRequestTimestamp = 0L
    private var lastOcrModeAnalysisTimestampMs = 0L
    private var lastFrontDoorBox: RectF? = null
    private var lastFrontDoorChosenTimestampMs = 0L
    private var lastFrameTimestampMs = 0L
    private var processedFrameCount = 0
    private var fpsSum = 0.0
    private var minFps = Float.MAX_VALUE
    private var maxFps = 0f
    private var inferenceMsSum = 0.0
    private var maxInferenceMs = 0.0
    private var frameMsSum = 0.0
    private var maxFrameMs = 0.0
    private var detectionCountSum = 0
    private var lastInferenceTimeMs = 0.0
    private var ocrCount = 0
    private var ocrMsSum = 0.0
    private var maxOcrMs = 0.0
    private var pendingRouteNumber: String? = null
    private var pendingRouteHits = 0
    private var pendingRouteTimestampMs = 0L

    private var modelInputSize = 320
    private var candidateCount = 2100
    private val scoreThreshold = 0.4f
    private val bollardScoreThreshold = 0.3f
    private val iouThreshold = 0.45f
    private val maxDetections = 30
    private val maxNmsCandidates = 160

    // --- [최적화 추가] 프레임 단위 가비지 컬렉터(GC) 호출 방지를 위한 메모리 사전 할당 ---
    private lateinit var inputBuffer: ByteBuffer
    private lateinit var pixels: IntArray
    private lateinit var modelInputBitmap: Bitmap
    private lateinit var modelInputCanvas: Canvas
    private var outputFloatArray: Array<Array<FloatArray>>? = null
    private var outputByteBuffer: ByteBuffer? = null
    private val frameMatrix = Matrix()
    private val preprocessMatrix = Matrix()
    private val preprocessPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var lastInputScale = 1f
    private var lastInputPadX = 0f
    private var lastInputPadY = 0f

    data class DetectionResult(
        val label: String,
        val score: Float,
        val classId: Int,
        val box: RectF
    )

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val isAllGranted = permissions.values.all { it == true }

        if (isAllGranted) {
            startCamera()
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
                val languageResult = tts?.setLanguage(Locale.KOREAN)
                isTtsReady = languageResult != android.speech.tts.TextToSpeech.LANG_MISSING_DATA &&
                    languageResult != android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
                Log.i(TAG, "TTS ready=$isTtsReady languageResult=$languageResult")
            } else {
                isTtsReady = false
                Log.w(TAG, "TTS init failed status=$status")
            }
        }

        try {
            labels = loadLabels("labels.txt")
            Log.d(TAG, "labels loaded: ${labels.size}")

            val useQuantizedModel = MODEL_ASSET_NAME.contains("int8", ignoreCase = true) ||
                MODEL_ASSET_NAME.contains("uint8", ignoreCase = true) ||
                MODEL_ASSET_NAME.contains("quant", ignoreCase = true)
            val compatList = CompatibilityList()
            val options = Interpreter.Options().apply {
                if (!useQuantizedModel && compatList.isDelegateSupportedOnThisDevice) {
                    val delegate = GpuDelegate()
                    gpuDelegate = delegate
                    this.addDelegate(delegate)
                    inferenceBackend = "gpu"
                    Log.d(TAG, "GPU Delegate Activated")
                } else {
                    val threadCount = max(1, min(4, Runtime.getRuntime().availableProcessors() - 1))
                    this.setNumThreads(threadCount)
                    this.setUseXNNPACK(true)
                    inferenceBackend = if (useQuantizedModel) {
                        "int8_cpu_xnnpack_${threadCount}threads"
                    } else {
                        "cpu_xnnpack_${threadCount}threads"
                    }
                    Log.d(TAG, "CPU Threads Activated: $threadCount")
                }
            }

            interpreter = Interpreter(
                loadModelFile(MODEL_ASSET_NAME),
                options
            )

            initTensorBuffers()

            Log.d(TAG, "TFLite model loaded and memory pre-allocated")
            logModelInfo()
            logDeviceInfo()
            logBenchmarkConfig()
        } catch (e: Exception) {
            Log.e(TAG, "모델 또는 labels 로드 실패", e)
            Toast.makeText(this, "모델 로드 실패: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.btnBack.setOnClickListener { handleBackAction() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackAction()
            }
        })

        binding.btnScan.setOnClickListener { view ->
            if (isScanning) {
                return@setOnClickListener
            }

            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            resetMetrics()
            byteTracker.reset()
            isScanning = true
            binding.btnScan.visibility = View.GONE
            binding.layoutResult.visibility = View.VISIBLE
            speakOut("실시간 환경 탐지를 시작합니다.")
        }

        binding.btnPlayTTS.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            speakOut(binding.tvResultDesc.text.toString())
        }

        val hasCam =
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        val hasLoc =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (hasCam && hasLoc) {
            startCamera()
        } else {
            requestPermissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun logModelInfo() {
        val inputTensor = interpreter.getInputTensor(0)
        val outputTensor = interpreter.getOutputTensor(0)

        Log.d(TAG, "Input shape: ${inputTensor.shape().contentToString()}")
        Log.d(TAG, "Input type: ${inputTensor.dataType()}")
        Log.d(TAG, "Output shape: ${outputTensor.shape().contentToString()}")
        Log.d(TAG, "Output type: ${outputTensor.dataType()}")
    }

    private fun initTensorBuffers() {
        val inputTensor = interpreter.getInputTensor(0)
        val outputTensor = interpreter.getOutputTensor(0)
        val inputShape = inputTensor.shape()
        val outputShape = outputTensor.shape()

        modelInputSize = inputShape.getOrNull(1) ?: modelInputSize
        candidateCount = outputShape.getOrNull(2) ?: candidateCount
        inputDataType = inputTensor.dataType()
        outputDataType = outputTensor.dataType()

        val inputQuant = inputTensor.quantizationParams()
        inputScale = inputQuant.getScale().takeIf { it > 0f } ?: 1f
        inputZeroPoint = inputQuant.getZeroPoint()
        useFastInt8InputQuantization =
            inputDataType == DataType.INT8 &&
                kotlin.math.abs(inputScale - (1f / 255f)) < 0.00001f &&
                inputZeroPoint == -128

        val outputQuant = outputTensor.quantizationParams()
        outputScale = outputQuant.getScale().takeIf { it > 0f } ?: 1f
        outputZeroPoint = outputQuant.getZeroPoint()

        inputBuffer = ByteBuffer.allocateDirect(inputTensor.numBytes()).apply {
            order(ByteOrder.nativeOrder())
        }
        pixels = IntArray(modelInputSize * modelInputSize)
        modelInputBitmap = Bitmap.createBitmap(modelInputSize, modelInputSize, Bitmap.Config.ARGB_8888)
        modelInputCanvas = Canvas(modelInputBitmap)

        if (outputDataType == DataType.FLOAT32) {
            outputFloatArray = Array(1) { Array(4 + labels.size) { FloatArray(candidateCount) } }
            outputByteBuffer = null
        } else {
            outputFloatArray = null
            outputByteBuffer = ByteBuffer.allocateDirect(outputTensor.numBytes()).apply {
                order(ByteOrder.nativeOrder())
            }
        }

        Log.i(
            METRIC_TAG,
            "TENSOR_CONFIG inputShape=${inputShape.contentToString()} inputType=$inputDataType " +
                "inputBytes=${inputTensor.numBytes()} outputShape=${outputShape.contentToString()} " +
                "outputType=$outputDataType outputBytes=${outputTensor.numBytes()} candidates=$candidateCount " +
                "fastInt8Input=$useFastInt8InputQuantization"
        )
    }

    private fun logDeviceInfo() {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        Log.i(
            METRIC_TAG,
            "DEVICE_INFO manufacturer=${Build.MANUFACTURER}, model=${Build.MODEL}, " +
                "sdk=${Build.VERSION.SDK_INT}, os=${Build.VERSION.RELEASE}, " +
                "ramMb=${memoryInfo.totalMem / (1024 * 1024)}, " +
                "cpuAbis=${Build.SUPPORTED_ABIS.joinToString("/")}, backend=$inferenceBackend"
        )
    }

    private fun logBenchmarkConfig() {
        Log.i(
            METRIC_TAG,
            "BENCHMARK_CONFIG analysis=${ANALYSIS_TARGET_WIDTH}x$ANALYSIS_TARGET_HEIGHT, " +
                "modelInput=${modelInputSize}x$modelInputSize, backend=$inferenceBackend, " +
                "targets=[fps>=$BENCHMARK_TARGET_FPS, frameMs<=$BENCHMARK_TARGET_FRAME_MS, " +
                "inferenceMs<=$BENCHMARK_TARGET_INFERENCE_MS, ocrMs<=$BENCHMARK_TARGET_OCR_MS]"
        )
    }

    private fun resetMetrics() {
        lastFrameTimestampMs = 0L
        processedFrameCount = 0
        fpsSum = 0.0
        minFps = Float.MAX_VALUE
        maxFps = 0f
        inferenceMsSum = 0.0
        maxInferenceMs = 0.0
        frameMsSum = 0.0
        maxFrameMs = 0.0
        detectionCountSum = 0
        lastInferenceTimeMs = 0.0
        ocrCount = 0
        ocrMsSum = 0.0
        maxOcrMs = 0.0
        lastOcrRequestTimestamp = 0L
        pendingRouteNumber = null
        pendingRouteHits = 0
        pendingRouteTimestampMs = 0L
        Log.i(METRIC_TAG, "METRICS_RESET target=$targetBusNumber")
        logDeviceInfo()
        logBenchmarkConfig()
    }

    private fun logFrameMetrics(
        frameProcessTimeMs: Double,
        inferenceTimeMs: Double,
        detectionCount: Int
    ) {
        val nowMs = SystemClock.elapsedRealtime()
        val fps = if (lastFrameTimestampMs > 0L) {
            1000f / max(1L, nowMs - lastFrameTimestampMs)
        } else {
            0f
        }

        lastFrameTimestampMs = nowMs
        processedFrameCount += 1

        if (fps > 0f) {
            fpsSum += fps.toDouble()
            minFps = min(minFps, fps)
            maxFps = max(maxFps, fps)
        }

        inferenceMsSum += inferenceTimeMs
        maxInferenceMs = max(maxInferenceMs, inferenceTimeMs)
        frameMsSum += frameProcessTimeMs
        maxFrameMs = max(maxFrameMs, frameProcessTimeMs)
        detectionCountSum += detectionCount

        if (processedFrameCount % PERF_FRAME_LOG_INTERVAL == 0) {
            Log.i(
                METRIC_TAG,
                "PERF_FRAME frame=$processedFrameCount fps=${fmt(fps.toDouble())} " +
                    "inferenceMs=${fmt(inferenceTimeMs)} frameProcessMs=${fmt(frameProcessTimeMs)} " +
                    "detections=$detectionCount"
            )
        }

        if (processedFrameCount % BENCHMARK_FRAME_WINDOW == 0) {
            logBenchmarkTable()
        }
    }

    private fun logOcrMetrics(ocrTimeMs: Double) {
        ocrCount += 1
        ocrMsSum += ocrTimeMs
        maxOcrMs = max(maxOcrMs, ocrTimeMs)

        Log.i(METRIC_TAG, "OCR_COMPLETE count=$ocrCount ocrMs=${fmt(ocrTimeMs)}")
    }

    private fun logBenchmarkTable() {
        val measuredFpsFrames = max(1, processedFrameCount - 1)
        val avgFps = fpsSum / measuredFpsFrames
        val avgFrameMs = frameMsSum / processedFrameCount
        val avgInferenceMs = inferenceMsSum / processedFrameCount
        val avgDetections = detectionCountSum.toDouble() / processedFrameCount
        val avgOcrMs = if (ocrCount > 0) ocrMsSum / ocrCount else 0.0
        val minFpsForLog = if (minFps == Float.MAX_VALUE) 0.0 else minFps.toDouble()

        Log.i(
            METRIC_TAG,
            "BENCHMARK_SUMMARY window=$processedFrameCount backend=$inferenceBackend " +
                "avgFps=${fmt(avgFps)} avgFrameMs=${fmt(avgFrameMs)} " +
                "avgTfliteMs=${fmt(avgInferenceMs)} avgOcrMs=${fmt(avgOcrMs)}"
        )

        Log.i(
            METRIC_TAG,
            """
            BENCHMARK_TABLE window=$processedFrameCount backend=$inferenceBackend
            | metric        | current | target | result |
            | avg_fps       | ${fmt(avgFps)} | >= ${fmt(BENCHMARK_TARGET_FPS)} | ${pass(avgFps >= BENCHMARK_TARGET_FPS)} |
            | min_fps       | ${fmt(minFpsForLog)} | >= ${fmt(BENCHMARK_TARGET_FPS)} | ${pass(minFpsForLog >= BENCHMARK_TARGET_FPS)} |
            | max_fps       | ${fmt(maxFps.toDouble())} | - | info |
            | avg_frame_ms  | ${fmt(avgFrameMs)} | <= ${fmt(BENCHMARK_TARGET_FRAME_MS)} | ${pass(avgFrameMs <= BENCHMARK_TARGET_FRAME_MS)} |
            | max_frame_ms  | ${fmt(maxFrameMs)} | <= ${fmt(BENCHMARK_TARGET_FRAME_MS)} | ${pass(maxFrameMs <= BENCHMARK_TARGET_FRAME_MS)} |
            | avg_tflite_ms | ${fmt(avgInferenceMs)} | <= ${fmt(BENCHMARK_TARGET_INFERENCE_MS)} | ${pass(avgInferenceMs <= BENCHMARK_TARGET_INFERENCE_MS)} |
            | max_tflite_ms | ${fmt(maxInferenceMs)} | <= ${fmt(BENCHMARK_TARGET_INFERENCE_MS)} | ${pass(maxInferenceMs <= BENCHMARK_TARGET_INFERENCE_MS)} |
            | avg_ocr_ms    | ${fmt(avgOcrMs)} | <= ${fmt(BENCHMARK_TARGET_OCR_MS)} | ${if (ocrCount > 0) pass(avgOcrMs <= BENCHMARK_TARGET_OCR_MS) else "no_sample"} |
            | max_ocr_ms    | ${fmt(maxOcrMs)} | <= ${fmt(BENCHMARK_TARGET_OCR_MS)} | ${if (ocrCount > 0) pass(maxOcrMs <= BENCHMARK_TARGET_OCR_MS) else "no_sample"} |
            | avg_detect    | ${fmt(avgDetections)} | - | info |
            """.trimIndent()
        )
    }

    private fun pass(isPassed: Boolean): String = if (isPassed) "pass" else "check"

    private fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun loadModelFile(assetName: String): MappedByteBuffer {
        val fileDescriptor = assets.openFd(assetName)
        val inputStream = fileDescriptor.createInputStream()
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun loadLabels(assetName: String): List<String> {
        val result = mutableListOf<String>()

        assets.open(assetName).use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
                lines.forEach { line ->
                    val label = line.trim()
                    if (label.isNotEmpty()) {
                        result.add(label)
                    }
                }
            }
        }

        return result
    }

    private fun bitmapToInputBuffer(bitmap: Bitmap): ByteBuffer {
        val scale = min(
            modelInputSize.toFloat() / bitmap.width.toFloat(),
            modelInputSize.toFloat() / bitmap.height.toFloat()
        )
        val scaledWidth = bitmap.width * scale
        val scaledHeight = bitmap.height * scale
        val padX = (modelInputSize - scaledWidth) / 2f
        val padY = (modelInputSize - scaledHeight) / 2f

        lastInputScale = scale
        lastInputPadX = padX
        lastInputPadY = padY

        modelInputBitmap.eraseColor(Color.rgb(114, 114, 114))
        preprocessMatrix.reset()
        preprocessMatrix.postScale(scale, scale)
        preprocessMatrix.postTranslate(padX, padY)
        modelInputCanvas.drawBitmap(bitmap, preprocessMatrix, preprocessPaint)

        // [최적화 수정] 매번 allocate 하지 않고 기존 버퍼를 초기화 후 재사용
        inputBuffer.rewind()

        modelInputBitmap.getPixels(
            pixels,
            0,
            modelInputSize,
            0,
            0,
            modelInputSize,
            modelInputSize
        )

        for (pixel in pixels) {
            putInputChannel((pixel shr 16) and 0xFF)
            putInputChannel((pixel shr 8) and 0xFF)
            putInputChannel(pixel and 0xFF)
        }

        inputBuffer.rewind()
        
        // Reuses the preallocated letterbox bitmap to avoid per-frame bitmap churn.
        
        return inputBuffer
    }

    private fun putInputChannel(channelValue: Int) {
        when (inputDataType) {
            DataType.FLOAT32 -> inputBuffer.putFloat(channelValue / 255.0f)
            DataType.UINT8 -> inputBuffer.put(channelValue.coerceIn(0, 255).toByte())
            DataType.INT8 -> {
                val quantizedValue = if (useFastInt8InputQuantization) {
                    channelValue - 128
                } else {
                    val normalizedValue = channelValue / 255.0f
                    (normalizedValue / inputScale + inputZeroPoint)
                        .roundToInt()
                        .coerceIn(-128, 127)
                }
                inputBuffer.put(quantizedValue.toByte())
            }
            else -> throw IllegalStateException("Unsupported input tensor type: $inputDataType")
        }
    }

    private fun runYoloRawTflite(bitmap: Bitmap): List<DetectionResult> {
        // [최적화 수정]
        val buffer = bitmapToInputBuffer(bitmap) 

        // [최적화 수정] 거대한 Array를 매 프레임 생성하지 않고, 미리 할당된 outputArray 재사용
        val inferenceStartNs = SystemClock.elapsedRealtimeNanos()
        val floatOutput = outputFloatArray
        val byteOutput = outputByteBuffer
        if (floatOutput != null) {
            interpreter.run(buffer, floatOutput)
        } else if (byteOutput != null) {
            byteOutput.rewind()
            interpreter.run(buffer, byteOutput)
            byteOutput.rewind()
        } else {
            throw IllegalStateException("Output buffer is not initialized")
        }
        lastInferenceTimeMs = (SystemClock.elapsedRealtimeNanos() - inferenceStartNs) / 1_000_000.0

        val detections = mutableListOf<DetectionResult>()

        val imgWidth = bitmap.width.toFloat()
        val imgHeight = bitmap.height.toFloat()

        for (i in 0 until candidateCount) {
            var cx = getOutputValue(0, i)
            var cy = getOutputValue(1, i)
            var w = getOutputValue(2, i)
            var h = getOutputValue(3, i)

            var bestClassId = -1
            var bestScore = 0f

            for (classId in labels.indices) {
                val score = getOutputValue(4 + classId, i)
                if (score > bestScore) {
                    bestScore = score
                    bestClassId = classId
                }
            }

            if (bestClassId !in labels.indices) continue
            if (bestScore < scoreThresholdFor(bestClassId)) continue

            val looksNormalized = cx <= 1.5f && cy <= 1.5f && w <= 1.5f && h <= 1.5f

            if (looksNormalized) {
                cx *= modelInputSize
                cy *= modelInputSize
                w *= modelInputSize
                h *= modelInputSize
            }

            val x1 = (cx - w / 2f - lastInputPadX) / lastInputScale
            val y1 = (cy - h / 2f - lastInputPadY) / lastInputScale
            val x2 = (cx + w / 2f - lastInputPadX) / lastInputScale
            val y2 = (cy + h / 2f - lastInputPadY) / lastInputScale

            val left = x1.coerceIn(0f, imgWidth)
            val top = y1.coerceIn(0f, imgHeight)
            val right = x2.coerceIn(0f, imgWidth)
            val bottom = y2.coerceIn(0f, imgHeight)

            if (right <= left || bottom <= top) continue

            detections.add(
                DetectionResult(
                    label = labels[bestClassId],
                    score = bestScore,
                    classId = bestClassId,
                    box = RectF(left, top, right, bottom)
                )
            )
        }

        val nmsInput = if (detections.size > maxNmsCandidates) {
            detections.sortedByDescending { it.score }.take(maxNmsCandidates)
        } else {
            detections
        }

        val nmsResults = applyNms(nmsInput, iouThreshold)
            .sortedByDescending { it.score }
            .take(maxDetections)

        return keepOnlyPrimaryBus(nmsResults, imgWidth, imgHeight)
    }

    private fun scoreThresholdFor(classId: Int): Float {
        return when {
            labels.getOrNull(classId).equals("bollard", ignoreCase = true) -> bollardScoreThreshold
            labels.getOrNull(classId).equals("bus", ignoreCase = true) -> BUS_MODEL_SCORE_THRESHOLD
            labels.getOrNull(classId).equals("bus_door", ignoreCase = true) -> DOOR_MODEL_SCORE_THRESHOLD
            else -> scoreThreshold
        }
    }

    private fun keepOnlyPrimaryBus(
        detections: List<DetectionResult>,
        imageWidth: Float,
        imageHeight: Float
    ): List<DetectionResult> {
        val nonBusDetections = detections.filterNot { it.label.equals("bus", ignoreCase = true) }
        val primaryBus = detections
            .asSequence()
            .filter { it.label.equals("bus", ignoreCase = true) }
            .filter { isLikelyPrimaryBus(it, imageWidth, imageHeight) }
            .maxByOrNull { it.score }

        return if (primaryBus != null) {
            (nonBusDetections + primaryBus).sortedByDescending { it.score }
        } else {
            nonBusDetections.sortedByDescending { it.score }
        }
    }

    private fun isLikelyPrimaryBus(
        detection: DetectionResult,
        imageWidth: Float,
        imageHeight: Float
    ): Boolean {
        if (detection.score < BUS_MODEL_SCORE_THRESHOLD) return false

        val box = detection.box
        val imageArea = (imageWidth * imageHeight).coerceAtLeast(1f)
        val areaRatio = rectArea(box) / imageArea
        val aspect = box.width() / max(1f, box.height())
        val valid = areaRatio >= BUS_MIN_AREA_RATIO &&
            aspect in BUS_MIN_ASPECT_RATIO..BUS_MAX_ASPECT_RATIO

        if (!valid) {
            Log.i(
                METRIC_TAG,
                "BUS_REJECT score=${fmt(detection.score.toDouble())} " +
                    "area=${fmt(areaRatio.toDouble())} aspect=${fmt(aspect.toDouble())} box=${rectToLog(box)}"
            )
        }

        return valid
    }

    private fun getOutputValue(channel: Int, candidateIndex: Int): Float {
        outputFloatArray?.let { return it[0][channel][candidateIndex] }

        val buffer = outputByteBuffer ?: throw IllegalStateException("Output buffer is not initialized")
        val index = channel * candidateCount + candidateIndex

        return when (outputDataType) {
            DataType.UINT8 -> {
                val raw = buffer.get(index).toInt() and 0xFF
                (raw - outputZeroPoint) * outputScale
            }
            DataType.INT8 -> {
                val raw = buffer.get(index).toInt()
                (raw - outputZeroPoint) * outputScale
            }
            else -> throw IllegalStateException("Unsupported output tensor type: $outputDataType")
        }
    }

    private fun applyNms(
        detections: List<DetectionResult>,
        iouThreshold: Float
    ): List<DetectionResult> {
        val result = mutableListOf<DetectionResult>()

        val grouped = detections.groupBy { it.classId }

        for ((_, classDetections) in grouped) {
            val sorted = classDetections.sortedByDescending { it.score }.toMutableList()

            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                result.add(best)

                val iterator = sorted.iterator()
                while (iterator.hasNext()) {
                    val other = iterator.next()
                    val iou = calculateIou(best.box, other.box)

                    if (iou > iouThreshold) {
                        iterator.remove()
                    }
                }
            }
        }

        return result
    }

    private fun calculateIou(a: RectF, b: RectF): Float {
        val intersectionLeft = max(a.left, b.left)
        val intersectionTop = max(a.top, b.top)
        val intersectionRight = min(a.right, b.right)
        val intersectionBottom = min(a.bottom, b.bottom)

        val intersectionWidth = max(0f, intersectionRight - intersectionLeft)
        val intersectionHeight = max(0f, intersectionBottom - intersectionTop)
        val intersectionArea = intersectionWidth * intersectionHeight

        val areaA = max(0f, a.width()) * max(0f, a.height())
        val areaB = max(0f, b.width()) * max(0f, b.height())

        val unionArea = areaA + areaB - intersectionArea

        if (unionArea <= 0f) return 0f

        return intersectionArea / unionArea
    }

    private fun rectArea(rect: RectF): Float {
        return max(0f, rect.width()) * max(0f, rect.height())
    }

    private fun intersectionArea(a: RectF, b: RectF): Float {
        val intersectionLeft = max(a.left, b.left)
        val intersectionTop = max(a.top, b.top)
        val intersectionRight = min(a.right, b.right)
        val intersectionBottom = min(a.bottom, b.bottom)

        val w = max(0f, intersectionRight - intersectionLeft)
        val h = max(0f, intersectionBottom - intersectionTop)
        return w * h
    }

    private fun calculateIoA(intersectionOverThis: RectF, other: RectF): Float {
        val inter = intersectionArea(intersectionOverThis, other)
        val denom = rectArea(intersectionOverThis)
        if (denom <= 0f) return 0f
        return inter / denom
    }

    private fun filterBusDoorCandidates(
        doors: List<DetectionResult>,
        busBox: RectF,
        imageWidth: Float,
        imageHeight: Float
    ): List<DetectionResult> {
        if (doors.isEmpty()) return doors

        val busArea = rectArea(busBox).coerceAtLeast(1f)
        val busCx = busBox.centerX()
        val busCy = busBox.centerY()

        val filtered = doors.filter { door ->
            if (door.score < DOOR_MIN_SCORE) return@filter false

            val d = door.box
            // 화면 밖으로 많이 나간 박스 제거(잡음 방지)
            if (d.left < -imageWidth * 0.05f || d.top < -imageHeight * 0.05f ||
                d.right > imageWidth * 1.05f || d.bottom > imageHeight * 1.05f
            ) return@filter false

            // bus bbox 안에 어느 정도 포함되는지 (intersection / doorArea)
            val ioa = calculateIoA(d, busBox)
            if (ioa < DOOR_MIN_IOA_WITH_BUS) return@filter false

            // bus bbox 대비 면적 비율
            val areaRatio = rectArea(d) / busArea
            if (areaRatio < DOOR_MIN_AREA_RATIO_TO_BUS || areaRatio > DOOR_MAX_AREA_RATIO_TO_BUS) return@filter false

            // 종횡비(문은 보통 너무 극단적으로 길쭉하지 않음)
            val aspect = d.width() / max(1f, d.height())
            if (aspect < DOOR_MIN_ASPECT_RATIO || aspect > DOOR_MAX_ASPECT_RATIO) return@filter false

            // bus 중심과 너무 동떨어진 도어는 제거(전혀 관계 없는 오탐 방지)
            val dx = kotlin.math.abs(d.centerX() - busCx) / max(1f, busBox.width())
            val dy = kotlin.math.abs(d.centerY() - busCy) / max(1f, busBox.height())
            if (dx > 0.9f || dy > 0.9f) return@filter false

            true
        }

        return filtered
    }

    private fun selectFrontDoor(
        plateBox: RectF,
        doors: List<DetectionResult>,
        busBox: RectF
    ): DetectionResult? {
        if (doors.isEmpty()) return null

        val nowMs = SystemClock.elapsedRealtime()
        val prev = lastFrontDoorBox
        if (prev != null && (nowMs - lastFrontDoorChosenTimestampMs) <= FRONT_DOOR_SMOOTHING_WINDOW_MS) {
            val bestMatchToPrev = doors.maxByOrNull { d -> calculateIou(prev, d.box) }
            if (bestMatchToPrev != null && calculateIou(prev, bestMatchToPrev.box) >= FRONT_DOOR_SMOOTHING_MIN_IOU) {
                // 직전 선택과 충분히 겹치면 그대로 유지 (프레임 간 흔들림 완화)
                lastFrontDoorBox = RectF(bestMatchToPrev.box)
                lastFrontDoorChosenTimestampMs = nowMs
                return bestMatchToPrev
            }
        }

        val plateCx = plateBox.centerX()
        val busCx = busBox.centerX()
        val busDiag = sqrt(busBox.width() * busBox.width() + busBox.height() * busBox.height()).coerceAtLeast(1f)

        // plate와의 거리 + "앞문은 대체로 버스의 우측/전방" priors를 섞어 스코어링
        val selected = doors.maxByOrNull { door ->
            val d = door.box
            val distNorm = distanceBetweenBoxes(plateBox, d) / busDiag

            val rightOfPlatePenalty = if (d.centerX() < plateCx) 0.40f else 0f
            val rightHalfPenalty = if (d.centerX() < busCx) 0.20f else 0f
            val lowerHalfPenalty = if (d.centerY() < busBox.centerY()) 0.10f else 0f

            // 높을수록 선택됨
            (door.score * 2.0f) - (distNorm * 1.0f) - rightOfPlatePenalty - rightHalfPenalty - lowerHalfPenalty
        }

        if (selected != null) {
            lastFrontDoorBox = RectF(selected.box)
            lastFrontDoorChosenTimestampMs = nowMs
        }

        return selected
    }

    private fun estimateFrontDoorBox(
        busBox: RectF,
        plateBox: RectF
    ): RectF {
        val busWidth = busBox.width().coerceAtLeast(1f)
        val busHeight = busBox.height().coerceAtLeast(1f)
        val frontOnRight = plateBox.centerX() >= busBox.centerX()
        val doorWidth = busWidth * 0.16f
        val doorHeight = busHeight * 0.58f
        val centerX = if (frontOnRight) {
            busBox.right - busWidth * 0.18f
        } else {
            busBox.left + busWidth * 0.18f
        }
        val top = busBox.top + busHeight * 0.28f
        val bottom = (top + doorHeight).coerceAtMost(busBox.bottom)

        return RectF(
            (centerX - doorWidth / 2f).coerceAtLeast(busBox.left),
            top.coerceAtLeast(busBox.top),
            (centerX + doorWidth / 2f).coerceAtMost(busBox.right),
            bottom
        )
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
        if (!isTtsReady || text.isBlank()) {
            Log.w(TAG, "TTS skipped ready=$isTtsReady textBlank=${text.isBlank()}")
            return
        }

        tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "")
    }

    private fun saveToDatabase(eventName: String) {
        if (
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                val lat = location?.latitude ?: 0.0
                val lon = location?.longitude ?: 0.0
                val sharedPref = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                val userId = sharedPref.getString("USER_ID", "알 수 없음") ?: "알 수 없음"

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        AppDatabase.getDatabase(this@CameraActivity).historyDao().insertHistory(
                            History(
                                userName = userId,
                                objectName = eventName,
                                latitude = lat,
                                longitude = lon
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "History save failed", e)
                    }
                }
            }
        }
    }

    /**
     * [최적화] ImageProxy.toBitmap() 대신 RGBA_8888 단일 plane을 직접 복사합니다.
     * rowStride 패딩이 있으면 행 단위로만 디패딩하여 불필요한 변환·복사를 줄입니다.
     */
    private fun imageProxyToRgbaBitmap(imageProxy: ImageProxy): Bitmap {
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer.duplicate()
        buffer.rewind()

        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = imageProxy.width
        val height = imageProxy.height

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        if (pixelStride == 4 && rowStride == width * 4) {
            bitmap.copyPixelsFromBuffer(buffer)
            return cropBitmapToProxyViewport(bitmap, imageProxy)
        }

        val row = ByteArray(rowStride)
        val rowPixels = IntArray(width)
        for (y in 0 until height) {
            buffer.get(row)
            var o = 0
            for (x in 0 until width) {
                val r = row[o].toInt() and 0xFF
                val g = row[o + 1].toInt() and 0xFF
                val b = row[o + 2].toInt() and 0xFF
                val a = row[o + 3].toInt() and 0xFF
                rowPixels[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                o += pixelStride
            }
            bitmap.setPixels(rowPixels, 0, width, 0, y, width, 1)
        }
        return cropBitmapToProxyViewport(bitmap, imageProxy)
    }

    private fun cropBitmapToProxyViewport(bitmap: Bitmap, imageProxy: ImageProxy): Bitmap {
        val crop = imageProxy.cropRect
        if (crop.left <= 0 && crop.top <= 0 && crop.right >= bitmap.width && crop.bottom >= bitmap.height) {
            return bitmap
        }

        val left = crop.left.coerceIn(0, bitmap.width - 1)
        val top = crop.top.coerceIn(0, bitmap.height - 1)
        val right = crop.right.coerceIn(left + 1, bitmap.width)
        val bottom = crop.bottom.coerceIn(top + 1, bitmap.height)
        val cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        bitmap.recycle()
        return cropped
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = try {
                cameraProviderFuture.get()
            } catch (exc: Throwable) {
                Log.e(TAG, "Camera provider init failed", exc)
                runOnUiThread {
                    Toast.makeText(this, "카메라를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                    binding.btnScan.isEnabled = false
                }
                return@addListener
            }

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(ANALYSIS_TARGET_WIDTH, ANALYSIS_TARGET_HEIGHT))
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (!isScanning) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        if (isOcrProcessing) {
                            // OCR 모드에서는 완전히 멈추지 않고,
                            // 일정 간격(OCR_MODE_ANALYSIS_INTERVAL_MS)으로만 분석을 수행합니다.
                            val nowMs = SystemClock.elapsedRealtime()
                            val elapsedSinceLastAnalyze = nowMs - lastOcrModeAnalysisTimestampMs
                            if (elapsedSinceLastAnalyze < OCR_MODE_ANALYSIS_INTERVAL_MS) {
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            lastOcrModeAnalysisTimestampMs = nowMs
                        }

                        val frameStartNs = SystemClock.elapsedRealtimeNanos()

                        try {
                            val bitmap = imageProxyToRgbaBitmap(imageProxy)

                            // [최적화 수정] 매트릭스 객체를 매 프레임 생성하지 않고 미리 만들어둔 객체 재사용
                            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                            val rotatedBitmap = if (rotationDegrees == 0) {
                                bitmap
                            } else {
                                frameMatrix.reset()
                                frameMatrix.postRotate(rotationDegrees.toFloat())
                                Bitmap.createBitmap(
                                    bitmap,
                                    0,
                                    0,
                                    bitmap.width,
                                    bitmap.height,
                                    frameMatrix,
                                    true
                                )
                            }

                            // [최적화 추가] 회전 처리 후 쓸모없어진 원본 비트맵 해제
                            if (bitmap != rotatedBitmap) {
                                bitmap.recycle()
                            }

                            val rawDetections = runYoloRawTflite(rotatedBitmap)
                        val detections = byteTracker.update(
                            rawDetections.map { ByteTracker.Detection(it.label, it.score, it.classId, it.box) }
                        ).map { DetectionResult(it.label, it.score, it.classId, it.box) }
                            val frameProcessTimeMs =
                                (SystemClock.elapsedRealtimeNanos() - frameStartNs) / 1_000_000.0

                            logFrameMetrics(
                                frameProcessTimeMs = frameProcessTimeMs,
                                inferenceTimeMs = lastInferenceTimeMs,
                                detectionCount = detections.size
                            )

                            if (detections.isNotEmpty()) {
                                handleDetectionResult(detections, rotatedBitmap, imageProxy)
                            } else {
                                runOnUiThread {
                                    binding.overlayView.clear()
                                    binding.tvResultDesc.text = "주변을 탐색 중입니다..."
                                }
                                rotatedBitmap.recycle()
                                imageProxy.close()
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "프레임 분석 중 오류", t)
                            runOnUiThread {
                                binding.tvResultDesc.text = "탐지 중 오류가 발생했습니다."
                            }
                            runCatching { imageProxy.close() }
                        }
                    }
                }

            val cameraSelector = chooseAvailableCamera(cameraProvider)
            if (cameraSelector == null) {
                Log.e(TAG, "No available camera on this device/emulator")
                Toast.makeText(this, "사용 가능한 카메라가 없습니다.", Toast.LENGTH_SHORT).show()
                binding.btnScan.isEnabled = false
                return@addListener
            }

            try {
                cameraProvider.unbindAll()
                val useCaseGroupBuilder = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(imageAnalyzer)

                binding.viewFinder.viewPort?.let { viewPort ->
                    useCaseGroupBuilder.setViewPort(viewPort)
                    Log.i(METRIC_TAG, "CAMERA_VIEWPORT enabled")
                } ?: Log.w(METRIC_TAG, "CAMERA_VIEWPORT unavailable")

                cameraProvider.bindToLifecycle(this, cameraSelector, useCaseGroupBuilder.build())
            } catch (exc: Throwable) {
                Log.e(TAG, "카메라 초기화 실패", exc)
                Toast.makeText(this, "카메라 초기화 실패", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun chooseAvailableCamera(cameraProvider: ProcessCameraProvider): CameraSelector? {
        return listOf(
            CameraSelector.DEFAULT_BACK_CAMERA,
            CameraSelector.DEFAULT_FRONT_CAMERA
        ).firstOrNull { selector ->
            try {
                cameraProvider.hasCamera(selector)
            } catch (exc: Throwable) {
                Log.w(TAG, "Camera selector unavailable: $selector", exc)
                false
            }
        }
    }

    private fun handleDetectionResult(
        detections: List<DetectionResult>,
        rotatedBitmap: Bitmap,
        imageProxy: androidx.camera.core.ImageProxy
    ) {
        val bus = detections
            .filter { it.label.equals("bus", true) }
            .maxByOrNull { it.score }

        val doors = detections
            .filter { it.label.equals("bus_door", true) }

        val detection = bus ?: detections.maxByOrNull { it.score } ?: run {
            rotatedBitmap.recycle()
            imageProxy.close()
            return
        }

        val category = detection.label
        val score = (detection.score * 100).toInt()
        val bbox = detection.box

        Log.i(
            METRIC_TAG,
            "DETECTION_SELECTED target=${targetBusNumber ?: "none"} category=$category score=$score " +
                "box=${rectToLog(bbox)} total=${detections.size} buses=${if (bus != null) 1 else 0} doors=${doors.size}"
        )

        val imgWidth = rotatedBitmap.width.toFloat()
        val centerX = bbox.centerX()

        val direction = when {
            centerX < imgWidth * 0.33f -> "10시"
            centerX > imgWidth * 0.66f -> "2시"
            else -> "12시"
        }

        val mappedBox = mapBoxToPreview(bbox, rotatedBitmap)

        if (category.equals("bus", true)) {
            val filteredDoors = filterBusDoorCandidates(
                doors = doors,
                busBox = bbox,
                imageWidth = rotatedBitmap.width.toFloat(),
                imageHeight = rotatedBitmap.height.toFloat()
            )
            val busAreaRatio = getBoxAreaRatio(bbox, rotatedBitmap)
            Log.i(
                METRIC_TAG,
                "ALGO_OCR_GATE target=$targetBusNumber busAreaRatio=${fmt(busAreaRatio.toDouble())} " +
                    "threshold=$BUS_OCR_AREA_RATIO_THRESHOLD action=${if (busAreaRatio >= BUS_OCR_AREA_RATIO_THRESHOLD) "run_ocr" else "skip_ocr"} " +
                    "busScore=$score doorCandidates=${doors.size} doorFiltered=${filteredDoors.size}"
            )

            val shouldRunOcr =
                busAreaRatio >= BUS_OCR_AREA_RATIO_THRESHOLD

            if (shouldRunOcr) {
                handleBusOcr(
                    category = category,
                    score = score,
                    bbox = bbox,
                    mappedBox = mappedBox,
                    direction = direction,
                    rotatedBitmap = rotatedBitmap,
                    doors = filteredDoors,
                    imageProxy = imageProxy
                )
            } else {
                val descText = "전방 $direction 방향에 버스가 감지되었습니다. 번호판 인식을 위해 조금 더 가까이 이동하세요."

                runOnUiThread {
                    binding.overlayView.setBoxInfo(
                        mappedBox,
                        Color.GREEN,
                        "bus ($score%)"
                    )
                    binding.tvResultDesc.text = descText
                }

                val currentTimestamp = System.currentTimeMillis()
                if (currentTimestamp - lastSavedTimestamp >= 5000) {
                    lastSavedTimestamp = currentTimestamp
                    speakOut(descText)
                    saveToDatabase(category)
                }

                rotatedBitmap.recycle()
                imageProxy.close()
            }
        } else {
            // 다중 탐지가 중요한 클래스(킥보드/볼라드)는 "선택된 1개 category"에 상관없이
            // 프레임 내 탐지된 해당 객체들을 우선적으로 모두 표시합니다.
            val multiDisplayLabels = setOf("kickboard", "bollard")
            val multiItems = detections
                .filter { it.label.lowercase(Locale.US) in multiDisplayLabels }
                .sortedByDescending { it.score }

            val itemsToShow = if (multiItems.isNotEmpty()) {
                multiItems.take(10)
            } else {
                detections
                    .filter { it.label.equals(category, true) }
                    .sortedByDescending { it.score }
                    .take(5)
            }

            val descText = when {
                multiItems.isNotEmpty() -> {
                    val kickboardCount = multiItems.count { it.label.equals("kickboard", true) }
                    val bollardCount = multiItems.count { it.label.equals("bollard", true) }
                    val parts = listOfNotNull(
                        kickboardCount.takeIf { it > 0 }?.let { "kickboard ${it}개" },
                        bollardCount.takeIf { it > 0 }?.let { "bollard ${it}개" }
                    )
                    "전방 $direction 방향에 ${parts.joinToString(", ")}가 감지되었습니다."
                }
                itemsToShow.size >= 2 -> "전방 $direction 방향에 $category ${itemsToShow.size}개가 감지되었습니다."
                else -> "전방 $direction 방향에 $category 가 감지되었습니다."
            }

            runOnUiThread {
                if (itemsToShow.size >= 2) {
                    val itemsForOverlay = itemsToShow.map { det ->
                        val mapped = mapBoxToPreview(det.box, rotatedBitmap)
                        OverlayView.BoxInfo(
                            rect = mapped,
                            color = Color.GREEN,
                            text = "${det.label} (${(det.score * 100).toInt()}%)"
                        )
                    }
                    binding.overlayView.setBoxesInfo(itemsForOverlay)
                } else {
                    binding.overlayView.setBoxInfo(
                        mappedBox,
                        Color.GREEN,
                        "$category ($score%)"
                    )
                }

                binding.tvResultDesc.text = descText
            }

            val currentTimestamp = System.currentTimeMillis()
            if (currentTimestamp - lastSavedTimestamp >= 5000) {
                lastSavedTimestamp = currentTimestamp
                speakOut(descText)
                saveToDatabase(category)
            }

            rotatedBitmap.recycle()
            imageProxy.close()
        }
    }

    private fun handleBusOcr(
        category: String,
        score: Int,
        bbox: RectF,
        mappedBox: RectF,
        direction: String,
        rotatedBitmap: Bitmap,
        doors: List<DetectionResult>,
        imageProxy: androidx.camera.core.ImageProxy
    ) {
        if (isOcrProcessing) {
            rotatedBitmap.recycle()
            imageProxy.close()
            return
        }

        val nowMs = SystemClock.elapsedRealtime()
        val minOcrIntervalMs = if (targetBusNumber.isNullOrBlank()) {
            OCR_NO_TARGET_MIN_INTERVAL_MS
        } else {
            OCR_MIN_INTERVAL_MS
        }
        if (nowMs - lastOcrRequestTimestamp < minOcrIntervalMs) {
            rotatedBitmap.recycle()
            imageProxy.close()
            return
        }
        lastOcrRequestTimestamp = nowMs
        isOcrProcessing = true

        val paddingX = bbox.width() * OCR_CROP_PADDING_RATIO
        val paddingY = bbox.height() * OCR_CROP_PADDING_RATIO
        val left = max(0, (bbox.left - paddingX).toInt())
        val top = max(0, (bbox.top - paddingY).toInt())
        val right = min(rotatedBitmap.width, (bbox.right + paddingX).toInt())
        val bottom = min(rotatedBitmap.height, (bbox.bottom + paddingY).toInt())
        val width = right - left
        val height = bottom - top

        if (width <= 0 || height <= 0) {
            isOcrProcessing = false
            rotatedBitmap.recycle()
            imageProxy.close()
            return
        }

        val croppedBitmap = Bitmap.createBitmap(
            rotatedBitmap,
            left,
            top,
            width,
            height
        )

        val ocrBitmap = upscaleBitmapForOcr(croppedBitmap)
        val inputImage = InputImage.fromBitmap(ocrBitmap, 0)
        val ocrStartNs = SystemClock.elapsedRealtimeNanos()

        Log.i(
            METRIC_TAG,
            "OCR_START target=$targetBusNumber crop=${width}x$height ocrBitmap=${ocrBitmap.width}x${ocrBitmap.height} " +
                "busBox=${rectToLog(bbox)} doorCandidates=${doors.size}"
        )

        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val recognizedText = visionText.text.replace(Regex("\\s"), "")
                val normalizedText = normalizeOcrText(visionText.text)
                val targetTextBox = targetBusNumber
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        findTargetTextBox(
                            visionText = visionText,
                            targetBusNumber = it,
                            cropLeft = left,
                            cropTop = top,
                            cropWidth = width,
                            cropHeight = height,
                            ocrWidth = ocrBitmap.width,
                            ocrHeight = ocrBitmap.height
                        )
                    }
                val routeCandidate = if (targetBusNumber.isNullOrBlank()) {
                    findBestRouteCandidate(
                        visionText = visionText,
                        cropLeft = left,
                        cropTop = top,
                        cropWidth = width,
                        cropHeight = height,
                        ocrWidth = ocrBitmap.width,
                        ocrHeight = ocrBitmap.height
                    )
                } else {
                    null
                }
                val isRouteConfirmed = if (targetTextBox != null && !targetBusNumber.isNullOrBlank()) {
                    updateRouteConfirmation(targetBusNumber!!)
                } else {
                    false
                }

                Log.i(
                    METRIC_TAG,
                    "OCR_RESULT target=$targetBusNumber matched=${targetTextBox != null} confirmed=$isRouteConfirmed " +
                        "candidate=${routeCandidate?.number ?: "none"} textLength=${recognizedText.length} normalizedLength=${normalizedText.length}"
                )

                if (targetBusNumber.isNullOrBlank()) {
                    val descText = if (routeCandidate != null) {
                        "${routeCandidate.number}번 버스가 감지되었습니다."
                    } else {
                        "전방 $direction 방향에 버스가 감지되었습니다."
                    }
                    val overlayLabel = routeCandidate?.number?.let { "버스 ${it}번" } ?: "bus ($score%)"

                    runOnUiThread {
                        binding.overlayView.setBoxInfo(
                            mappedBox,
                            Color.GREEN,
                            overlayLabel
                        )
                        binding.tvResultDesc.text = descText
                    }

                    val currentTimestamp = System.currentTimeMillis()
                    if (currentTimestamp - lastSavedTimestamp >= 5000) {
                        lastSavedTimestamp = currentTimestamp
                        speakOut(descText)
                        saveToDatabase(routeCandidate?.number?.let { "버스 감지 ($it)" } ?: category)
                    }
                } else if (isRouteConfirmed) {
                    val plateBox = targetTextBox ?: return@addOnSuccessListener

                    val frontDoor = selectFrontDoor(
                        plateBox = plateBox,
                        doors = doors,
                        busBox = bbox
                    )
                    val frontDoorBox = frontDoor?.box ?: estimateFrontDoorBox(
                        busBox = bbox,
                        plateBox = plateBox
                    )
                    val frontDoorDistancePx = frontDoor?.let {
                        distanceBetweenBoxes(plateBox, it.box)
                    }

                    val frontDoorDirection = getDirectionFromBox(frontDoorBox, rotatedBitmap.width.toFloat())

                    Log.i(
                        METRIC_TAG,
                        "ALGO_FRONT_DOOR target=$targetBusNumber plateBox=${rectToLog(plateBox)} " +
                            "doorCandidates=${doors.size} selectedDoor=${rectToLog(frontDoor?.box)} " +
                            "fallbackDoor=${frontDoor == null} frontDoorBox=${rectToLog(frontDoorBox)} " +
                            "distancePx=${frontDoorDistancePx?.let { fmt(it.toDouble()) } ?: "none"} " +
                            "direction=$frontDoorDirection"
                    )

                    val descText =
                        "(TTS) $targetBusNumber 번, 타겟 버스가 감지되었습니다. 앞문은 $frontDoorDirection 방향입니다."

                    val boxForOverlay = mapBoxToPreview(frontDoorBox, rotatedBitmap)

                    val overlayLabel = if (frontDoor != null) {
                        "앞문 (${targetBusNumber}번)"
                    } else {
                        "앞문 추정 (${targetBusNumber}번)"
                    }

                    runOnUiThread {
                        binding.overlayView.setBoxInfo(
                            boxForOverlay,
                            Color.MAGENTA,
                            overlayLabel
                        )
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
                    Log.i(
                        METRIC_TAG,
                        "ALGO_FRONT_DOOR target=$targetBusNumber skipped=target_not_matched doorCandidates=${doors.size}"
                    )

                    val busDetectedDescText = if (targetTextBox != null) {
                        "전방 $direction 방향에 버스가 감지되었습니다. 번호를 확인 중입니다."
                    } else {
                        "전방 $direction 방향에 버스/차량이 감지되었습니다."
                    }

                    runOnUiThread {
                        binding.overlayView.setBoxInfo(
                            mappedBox,
                            Color.GREEN,
                            "$category ($score%)"
                        )
                        binding.tvResultDesc.text = busDetectedDescText
                    }

                    val currentTimestamp = System.currentTimeMillis()
                    if (currentTimestamp - lastSavedTimestamp >= 5000) {
                        lastSavedTimestamp = currentTimestamp
                        speakOut(busDetectedDescText)
                        saveToDatabase(category)
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(METRIC_TAG, "OCR_FAILURE target=$targetBusNumber message=${e.message}", e)
                Log.e(TAG, "OCR 실패", e)
                runOnUiThread {
                    binding.overlayView.setBoxInfo(
                        mappedBox,
                        Color.GREEN,
                        "$category ($score%)"
                    )
                    binding.tvResultDesc.text = "OCR 처리 중 오류가 발생했습니다."
                }
            }
            .addOnCompleteListener {
                val ocrTimeMs = (SystemClock.elapsedRealtimeNanos() - ocrStartNs) / 1_000_000.0
                logOcrMetrics(ocrTimeMs)
                isOcrProcessing = false
                if (ocrBitmap != croppedBitmap) {
                    ocrBitmap.recycle()
                }
                croppedBitmap.recycle()
                rotatedBitmap.recycle()
                imageProxy.close()
            }
    }

    private fun rectToLog(rect: RectF?): String {
        if (rect == null) return "none"

        return "(${rect.left.toInt()},${rect.top.toInt()},${rect.right.toInt()},${rect.bottom.toInt()})"
    }

    private fun getBoxAreaRatio(box: RectF, bitmap: Bitmap): Float {
        val boxArea = max(0f, box.width()) * max(0f, box.height())
        val imageArea = bitmap.width.toFloat() * bitmap.height.toFloat()

        if (imageArea <= 0f) return 0f

        return boxArea / imageArea
    }

    private fun upscaleBitmapForOcr(bitmap: Bitmap): Bitmap {
        val longSide = max(bitmap.width, bitmap.height)
        if (longSide >= OCR_MIN_CROP_LONG_SIDE) {
            return bitmap
        }

        val scale = min(
            OCR_MAX_UPSCALE,
            OCR_MIN_CROP_LONG_SIDE.toFloat() / max(1, longSide).toFloat()
        )
        val scaledWidth = max(1, (bitmap.width * scale).roundToInt())
        val scaledHeight = max(1, (bitmap.height * scale).roundToInt())

        return Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
    }

    private fun normalizeOcrText(text: String): String {
        return text
            .uppercase(Locale.US)
            .replace(Regex("\\s"), "")
            .replace("O", "0")
            .replace("Q", "0")
            .replace("I", "1")
            .replace("L", "1")
            .replace("|", "1")
            .replace("S", "5")
            .replace("B", "8")
    }

    private fun normalizeOcrDigits(text: String): String {
        return normalizeOcrText(text).filter { it.isDigit() }
    }

    private fun updateRouteConfirmation(targetBusNumber: String): Boolean {
        val normalizedTarget = normalizeOcrDigits(targetBusNumber)
        if (normalizedTarget.isBlank()) return false

        val nowMs = SystemClock.elapsedRealtime()
        val isSameWindow = pendingRouteNumber == normalizedTarget &&
            nowMs - pendingRouteTimestampMs <= OCR_ROUTE_CONFIRM_WINDOW_MS

        if (isSameWindow) {
            pendingRouteHits += 1
        } else {
            pendingRouteNumber = normalizedTarget
            pendingRouteHits = 1
        }

        pendingRouteTimestampMs = nowMs

        Log.i(
            METRIC_TAG,
            "OCR_ROUTE_CONFIRM target=$targetBusNumber normalized=$normalizedTarget hits=$pendingRouteHits"
        )

        return pendingRouteHits >= OCR_ROUTE_CONFIRM_MIN_HITS
    }

    private fun isLikelyBusRouteText(
        text: String,
        targetBusNumber: String
    ): Boolean {
        val compactText = text.replace(Regex("\\s"), "")
        val targetDigits = normalizeOcrDigits(targetBusNumber)
        val routeCandidate = extractRouteNumberCandidate(text) ?: return false
        if (targetDigits.isBlank()) {
            return false
        }

        val isTargetMatched = if (targetDigits.length == 1) {
            routeCandidate == targetDigits
        } else {
            routeCandidate.contains(targetDigits)
        }
        if (!isTargetMatched) {
            return false
        }

        val maxRouteDigits = max(5, targetDigits.length + 2)
        if (routeCandidate.length > maxRouteDigits) {
            return false
        }

        return compactText.length <= 14
    }

    private fun isLikelyBusRouteBox(
        box: android.graphics.Rect,
        ocrWidth: Int,
        ocrHeight: Int
    ): Boolean {
        if (ocrWidth <= 0 || ocrHeight <= 0) return true

        val centerYRatio = box.centerY().toFloat() / ocrHeight.toFloat()
        val widthRatio = box.width().toFloat() / ocrWidth.toFloat()
        val heightRatio = box.height().toFloat() / ocrHeight.toFloat()

        if (centerYRatio > 0.78f) return false
        if (widthRatio > 0.65f) return false
        if (heightRatio < 0.025f) return false

        return true
    }

    private fun extractRouteNumberCandidate(text: String): String? {
        val compactText = text.replace(Regex("\\s"), "")
        if (Regex("""\d{2,3}[가-힣]\d{4}""").containsMatchIn(compactText)) {
            return null
        }

        val normalized = normalizeOcrText(text).replace(Regex("\\s"), "")
        if (normalized.length > 18) {
            return null
        }

        return Regex("""\d{1,5}""")
            .findAll(normalized)
            .map { it.value }
            .filterNot { it.length >= 3 && it.startsWith("0") }
            .maxWithOrNull(compareBy<String> { it.length }.thenBy { it })
    }

    private fun findBestRouteCandidate(
        visionText: Text,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        ocrWidth: Int,
        ocrHeight: Int
    ): RouteCandidate? {
        val scaleX = cropWidth.toFloat() / max(1, ocrWidth).toFloat()
        val scaleY = cropHeight.toFloat() / max(1, ocrHeight).toFloat()
        val candidates = mutableListOf<RouteCandidate>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val number = extractRouteNumberCandidate(line.text) ?: continue
                if (!isLikelyBusRouteBox(box, ocrWidth, ocrHeight)) {
                    Log.i(
                        METRIC_TAG,
                        "OCR_ROUTE_CANDIDATE_REJECT number=$number reason=geometry box=${box.flattenToString()}"
                    )
                    continue
                }

                val boxAreaRatio = box.width().toFloat() * box.height().toFloat() /
                    max(1, ocrWidth * ocrHeight).toFloat()
                val lengthBonus = number.length * 0.12f
                val fourDigitBonus = if (number.length == 4) 0.25f else 0f
                val score = boxAreaRatio + lengthBonus + fourDigitBonus
                val mappedBox = RectF(
                    box.left * scaleX + cropLeft.toFloat(),
                    box.top * scaleY + cropTop.toFloat(),
                    box.right * scaleX + cropLeft.toFloat(),
                    box.bottom * scaleY + cropTop.toFloat()
                )

                candidates += RouteCandidate(
                    number = number,
                    box = mappedBox,
                    score = score
                )

                Log.i(
                    METRIC_TAG,
                    "OCR_ROUTE_CANDIDATE number=$number score=${fmt(score.toDouble())} box=${box.flattenToString()}"
                )
            }
        }

        return candidates.maxByOrNull { it.score }
    }

    private fun findTargetTextBox(
        visionText: Text,
        targetBusNumber: String,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        ocrWidth: Int,
        ocrHeight: Int
    ): RectF? {
        val scaleX = cropWidth.toFloat() / max(1, ocrWidth).toFloat()
        val scaleY = cropHeight.toFloat() / max(1, ocrHeight).toFloat()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                if (!isLikelyBusRouteText(line.text, targetBusNumber)) continue
                if (!isLikelyBusRouteBox(box, ocrWidth, ocrHeight)) {
                    Log.i(
                        METRIC_TAG,
                        "OCR_ROUTE_REJECT target=$targetBusNumber reason=geometry text=${line.text} box=${box.flattenToString()}"
                    )
                    continue
                }

                Log.i(
                    METRIC_TAG,
                    "OCR_ROUTE_MATCH target=$targetBusNumber text=${line.text} box=${box.flattenToString()}"
                )

                return RectF(
                    box.left * scaleX + cropLeft.toFloat(),
                    box.top * scaleY + cropTop.toFloat(),
                    box.right * scaleX + cropLeft.toFloat(),
                    box.bottom * scaleY + cropTop.toFloat()
                )
            }
        }

        return null
    }

    private fun distanceBetweenBoxes(a: RectF, b: RectF): Float {
        val dx = a.centerX() - b.centerX()
        val dy = a.centerY() - b.centerY()
        return sqrt(dx * dx + dy * dy)
    }

    private fun getDirectionFromBox(box: RectF, imageWidth: Float): String {
        val centerX = box.centerX()

        return when {
            centerX < imageWidth * 0.33f -> "10시"
            centerX > imageWidth * 0.66f -> "2시"
            else -> "12시"
        }
    }

    private fun mapBoxToPreview(box: RectF, rotatedBitmap: Bitmap): RectF {
        val viewWidth = (binding.overlayView.width.takeIf { it > 0 } ?: binding.viewFinder.width).toFloat()
        val viewHeight = (binding.overlayView.height.takeIf { it > 0 } ?: binding.viewFinder.height).toFloat()
        val imageWidth = rotatedBitmap.width.toFloat()
        val imageHeight = rotatedBitmap.height.toFloat()

        if (viewWidth <= 0f || viewHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) {
            return RectF(box)
        }

        val scale = max(viewWidth / imageWidth, viewHeight / imageHeight)
        val offsetX = (viewWidth - imageWidth * scale) / 2f
        val offsetY = (viewHeight - imageHeight * scale) / 2f

        return RectF(
            box.left * scale + offsetX,
            box.top * scale + offsetY,
            box.right * scale + offsetX,
            box.bottom * scale + offsetY
        )
    }

    private fun handleBackAction() {
        if (binding.layoutResult.visibility == View.VISIBLE) {
            binding.layoutResult.visibility = View.GONE
            binding.btnScan.visibility = View.VISIBLE
            isScanning = false
            byteTracker.reset()
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
        interpreter.close()
        gpuDelegate?.close()
        textRecognizer.close()
    }
}
