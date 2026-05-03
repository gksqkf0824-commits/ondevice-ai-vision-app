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
import android.util.Log
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import kotlin.math.sqrt

class CameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraActivity"

        // 알고리즘 1:
        // 사용자 화면 기준 bus bounding box 면적이 전체 화면의 40% 이상일 때만 OCR 수행
        private const val BUS_OCR_AREA_RATIO_THRESHOLD = 0.4f
    }

    private lateinit var binding: ActivityCameraBinding
    private lateinit var cameraExecutor: ExecutorService
    private var tts: android.speech.tts.TextToSpeech? = null

    private lateinit var interpreter: Interpreter
    private lateinit var labels: List<String>

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val textRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    private var targetBusNumber: String? = null
    private var isScanning = false
    private var isOcrProcessing = false
    private var lastSavedTimestamp = 0L

    private val modelInputSize = 640
    private val candidateCount = 8400
    private val scoreThreshold = 0.4f
    private val iouThreshold = 0.45f
    private val maxDetections = 20

    // --- [최적화 추가] 프레임 단위 가비지 컬렉터(GC) 호출 방지를 위한 메모리 사전 할당 ---
    private lateinit var inputBuffer: ByteBuffer
    private lateinit var pixels: IntArray
    private lateinit var outputArray: Array<Array<FloatArray>>
    private val frameMatrix = Matrix()

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
                tts?.language = Locale.KOREAN
            }
        }

        try {
            labels = loadLabels("labels.txt")
            Log.d(TAG, "labels loaded: ${labels.size}")

            // --- [최적화 수정] GPU 하드웨어 가속 동적 할당 ---
            val compatList = CompatibilityList()
            val options = Interpreter.Options().apply {
                if (compatList.isDelegateSupportedOnThisDevice) {
                    val delegateOptions = compatList.bestOptionsForThisDevice
                    this.addDelegate(GpuDelegate(delegateOptions))
                    Log.d(TAG, "GPU Delegate Activated")
                } else {
                    this.setNumThreads(4)
                    Log.d(TAG, "CPU Threads Activated")
                }
            }

            interpreter = Interpreter(
                loadModelFile("BusProject_v11n_best_float16.tflite"),
                options
            )

            // --- [최적화 추가] TFLite 버퍼 및 배열 메모리 1회 초기화 ---
            inputBuffer = ByteBuffer.allocateDirect(1 * modelInputSize * modelInputSize * 3 * 4).apply {
                order(ByteOrder.nativeOrder())
            }
            pixels = IntArray(modelInputSize * modelInputSize)
            outputArray = Array(1) { Array(4 + labels.size) { FloatArray(candidateCount) } }

            Log.d(TAG, "TFLite model loaded and memory pre-allocated")
            logModelInfo()
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
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, modelInputSize, modelInputSize, true)

        // [최적화 수정] 매번 allocate 하지 않고 기존 버퍼를 초기화 후 재사용
        inputBuffer.rewind()

        resizedBitmap.getPixels(
            pixels,
            0,
            modelInputSize,
            0,
            0,
            modelInputSize,
            modelInputSize
        )

        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }

        inputBuffer.rewind()
        
        // [최적화 추가] 리사이즈용으로 생성된 임시 비트맵 즉시 파기 (메모리 확보)
        if (resizedBitmap != bitmap) {
            resizedBitmap.recycle()
        }
        
        return inputBuffer
    }

    private fun runYoloRawTflite(bitmap: Bitmap): List<DetectionResult> {
        // [최적화 수정]
        val buffer = bitmapToInputBuffer(bitmap) 

        // [최적화 수정] 거대한 Array를 매 프레임 생성하지 않고, 미리 할당된 outputArray 재사용
        interpreter.run(buffer, outputArray)

        val detections = mutableListOf<DetectionResult>()

        val imgWidth = bitmap.width.toFloat()
        val imgHeight = bitmap.height.toFloat()

        for (i in 0 until candidateCount) {
            var cx = outputArray[0][0][i]
            var cy = outputArray[0][1][i]
            var w = outputArray[0][2][i]
            var h = outputArray[0][3][i]

            var bestClassId = -1
            var bestScore = 0f

            for (classId in labels.indices) {
                val score = outputArray[0][4 + classId][i]
                if (score > bestScore) {
                    bestScore = score
                    bestClassId = classId
                }
            }

            if (bestScore < scoreThreshold) continue
            if (bestClassId !in labels.indices) continue

            val looksNormalized = cx <= 1.5f && cy <= 1.5f && w <= 1.5f && h <= 1.5f

            if (looksNormalized) {
                cx *= modelInputSize
                cy *= modelInputSize
                w *= modelInputSize
                h *= modelInputSize
            }

            val x1 = cx - w / 2f
            val y1 = cy - h / 2f
            val x2 = cx + w / 2f
            val y2 = cy + h / 2f

            val scaleXToOriginal = imgWidth / modelInputSize.toFloat()
            val scaleYToOriginal = imgHeight / modelInputSize.toFloat()

            val left = (x1 * scaleXToOriginal).coerceIn(0f, imgWidth)
            val top = (y1 * scaleYToOriginal).coerceIn(0f, imgHeight)
            val right = (x2 * scaleXToOriginal).coerceIn(0f, imgWidth)
            val bottom = (y2 * scaleYToOriginal).coerceIn(0f, imgHeight)

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

        val nmsResults = applyNms(detections, iouThreshold)
            .sortedByDescending { it.score }
            .take(maxDetections)

        return nmsResults
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
                    AppDatabase.getDatabase(this@CameraActivity).historyDao().insertHistory(
                        History(
                            userName = userId,
                            objectName = eventName,
                            latitude = lat,
                            longitude = lon
                        )
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

                        try {
                            val bitmap = imageProxy.toBitmap()

                            // [최적화 수정] 매트릭스 객체를 매 프레임 생성하지 않고 미리 만들어둔 객체 재사용
                            frameMatrix.reset()
                            frameMatrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                            val rotatedBitmap = Bitmap.createBitmap(
                                bitmap,
                                0,
                                0,
                                bitmap.width,
                                bitmap.height,
                                frameMatrix,
                                true
                            )

                            // [최적화 추가] 회전 처리 후 쓸모없어진 원본 비트맵 해제
                            if (bitmap != rotatedBitmap) {
                                bitmap.recycle()
                            }

                            val detections = runYoloRawTflite(rotatedBitmap)

                            if (detections.isNotEmpty()) {
                                handleDetectionResult(detections, rotatedBitmap, imageProxy)
                            } else {
                                runOnUiThread {
                                    binding.overlayView.clear()
                                    binding.tvResultDesc.text = "주변을 탐색 중입니다..."
                                }
                                imageProxy.close()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "프레임 분석 중 오류", e)
                            runOnUiThread {
                                binding.tvResultDesc.text = "탐지 중 오류가 발생했습니다."
                            }
                            imageProxy.close()
                        }
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
                Log.e(TAG, "카메라 초기화 실패", exc)
                Toast.makeText(this, "카메라 초기화 실패", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
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
            imageProxy.close()
            return
        }

        val category = detection.label
        val score = (detection.score * 100).toInt()
        val bbox = detection.box

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

        if (
            !targetBusNumber.isNullOrEmpty() &&
            category.equals("bus", true)
        ) {
            val busAreaRatio = getBoxAreaRatio(bbox, rotatedBitmap)

            if (busAreaRatio >= BUS_OCR_AREA_RATIO_THRESHOLD) {
                handleBusOcr(
                    category = category,
                    score = score,
                    bbox = bbox,
                    mappedBox = mappedBox,
                    direction = direction,
                    rotatedBitmap = rotatedBitmap,
                    doors = doors,
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

                imageProxy.close()
            }
        } else {
            val descText = "전방 $direction 방향에 $category 가 감지되었습니다."

            runOnUiThread {
                binding.overlayView.setBoxInfo(
                    mappedBox,
                    Color.GREEN,
                    "$category ($score%)"
                )
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
            imageProxy.close()
            return
        }

        isOcrProcessing = true

        val left = max(0, bbox.left.toInt())
        val top = max(0, bbox.top.toInt())
        val width = min(rotatedBitmap.width - left, bbox.width().toInt())
        val height = min(rotatedBitmap.height - top, bbox.height().toInt())

        if (width <= 0 || height <= 0) {
            isOcrProcessing = false
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

        val inputImage = InputImage.fromBitmap(croppedBitmap, 0)

        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val recognizedText = visionText.text.replace(Regex("\\s"), "")

                if (!targetBusNumber.isNullOrEmpty() && recognizedText.contains(targetBusNumber!!)) {
                    val plateBox = findTargetTextBox(
                        visionText = visionText,
                        targetBusNumber = targetBusNumber!!,
                        cropLeft = left,
                        cropTop = top
                    )

                    val frontDoor = if (plateBox != null) {
                        findNearestDoor(plateBox, doors)
                    } else {
                        null
                    }

                    val frontDoorDirection = if (frontDoor != null) {
                        getDirectionFromBox(frontDoor.box, rotatedBitmap.width.toFloat())
                    } else {
                        direction
                    }

                    val descText =
                        "(TTS) $targetBusNumber 번, 타겟 버스가 감지되었습니다. 앞문은 $frontDoorDirection 방향입니다."

                    val boxForOverlay = if (frontDoor != null) {
                        mapBoxToPreview(frontDoor.box, rotatedBitmap)
                    } else {
                        mappedBox
                    }

                    val overlayLabel = if (frontDoor != null) {
                        "앞문 (${targetBusNumber}번)"
                    } else {
                        "버스 ${targetBusNumber}번"
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
                    runOnUiThread {
                        binding.overlayView.setBoxInfo(
                            mappedBox,
                            Color.GREEN,
                            "$category ($score%)"
                        )
                        binding.tvResultDesc.text = "전방 $direction 방향에 버스/차량이 감지되었습니다."
                    }
                }
            }
            .addOnFailureListener { e ->
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
                isOcrProcessing = false
                imageProxy.close()
            }
    }

    private fun getBoxAreaRatio(box: RectF, bitmap: Bitmap): Float {
        val boxArea = max(0f, box.width()) * max(0f, box.height())
        val imageArea = bitmap.width.toFloat() * bitmap.height.toFloat()

        if (imageArea <= 0f) return 0f

        return boxArea / imageArea
    }

    private fun findTargetTextBox(
        visionText: Text,
        targetBusNumber: String,
        cropLeft: Int,
        cropTop: Int
    ): RectF? {
        val normalizedTarget = targetBusNumber.replace(Regex("\\s"), "")

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val lineText = line.text.replace(Regex("\\s"), "")

                if (lineText.contains(normalizedTarget)) {
                    val box = line.boundingBox ?: continue

                    return RectF(
                        box.left + cropLeft.toFloat(),
                        box.top + cropTop.toFloat(),
                        box.right + cropLeft.toFloat(),
                        box.bottom + cropTop.toFloat()
                    )
                }
            }
        }

        return null
    }

    private fun distanceBetweenBoxes(a: RectF, b: RectF): Float {
        val dx = a.centerX() - b.centerX()
        val dy = a.centerY() - b.centerY()
        return sqrt(dx * dx + dy * dy)
    }

    private fun findNearestDoor(
        plateBox: RectF,
        doors: List<DetectionResult>
    ): DetectionResult? {
        return doors.minByOrNull { door ->
            distanceBetweenBoxes(plateBox, door.box)
        }
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
        val scaleX = binding.viewFinder.width.toFloat() / rotatedBitmap.width.toFloat()
        val scaleY = binding.viewFinder.height.toFloat() / rotatedBitmap.height.toFloat()

        return RectF(
            box.left * scaleX,
            box.top * scaleY,
            box.right * scaleX,
            box.bottom * scaleY
        )
    }

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
        interpreter.close()
        textRecognizer.close()
    }
}