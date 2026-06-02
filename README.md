# 온길 — On-device AI Vision for the Visually Impaired

> 시각 약자를 위한 온디바이스 실시간 AI 탐지·안내 Android 앱

온길은 스마트폰 카메라만으로 버스·장애물을 실시간 탐지하고, 음성으로 탑승 위치를 안내하는 접근성 앱입니다.  
서버 없이 기기 내에서 추론이 완결되어 네트워크 환경에 관계없이 동작합니다.

---

## Features

| 기능 | 설명 |
|------|------|
| 실시간 객체 탐지 | YOLOv11n INT8 TFLite 모델로 버스·버스문·킥보드·볼라드 탐지 |
| 버스 번호 OCR | ML Kit 한국어 OCR로 버스 노선 번호판 인식 |
| 앞문 위치 안내 | 탐지된 버스에서 앞문 위치를 추정·안내 |
| 음성 입력(STT) | 탑승할 버스 번호를 음성으로 입력 |
| 음성 출력(TTS) | 탐지 결과를 실시간 음성으로 안내 |
| IoU 기반 트래킹 | ByteTracker로 프레임 간 탐지 끊김 방지 및 박스 스무딩 |
| 탐지 기록 | GPS 좌표와 함께 탐지 이력 저장 및 바 차트 시각화 |
| 보호자 연동 | 보호자 계정 연동으로 피보호자 상황 공유 |

---

## Detection Classes

```
bus          버스 전체 영역
bus_door     버스 앞문
kickboard    전동킥보드
bollard      볼라드(차도 경계석)
```

---

## How It Works

```
카메라 프레임
     │
     ▼
[CameraX RGBA_8888]
     │  letterbox + INT8 양자화
     ▼
[YOLOv11n TFLite]  ── 온디바이스 추론 (CPU XNNPACK)
     │  NMS → keepOnlyPrimaryBus
     ▼
[ByteTracker]  ── IoU 매칭 3단계 + EMA 스무딩
     │
     ├─ bus 탐지 & 면적 조건 충족
     │       │
     │       ▼
     │  [ML Kit OCR]  ── 노선 번호 인식 → 다중 프레임 확인(2회)
     │       │
     │       ▼
     │  앞문 위치 추정 → TTS 안내
     │
     └─ kickboard / bollard
             │
             ▼
        TTS 경고 안내
```

---

## Tech Stack

**Mobile (Android)**

| 영역 | 라이브러리 |
|------|-----------|
| 언어 | Kotlin |
| 카메라 | CameraX 1.3.0 |
| 추론 | TensorFlow Lite 2.16.1 + GPU Delegate |
| OCR | Google ML Kit Text Recognition Korean 16.0.1 |
| 트래킹 | ByteTracker (custom, pure Kotlin) |
| 로컬 DB | Room 2.6.1 |
| 위치 | Google Play Services Location |
| 차트 | MPAndroidChart |
| 네트워크 | Retrofit2 + Gson |
| UI | ViewBinding, ConstraintLayout, Material |

**AI Model**

| 항목 | 값 |
|------|----|
| 아키텍처 | YOLOv11n |
| 포맷 | TFLite INT8 (manual quantization) |
| 입력 크기 | 동적 감지 (initTensorBuffers) |
| 클래스 수 | 4 |
| 백엔드 | CPU XNNPACK |

---

## Project Structure

```
ondevice-ai-vision-app/
├── mobile/                        # Android 앱
│   └── app/src/main/
│       ├── java/com/example/ondevice/
│       │   ├── CameraActivity.kt  # 핵심: 카메라·추론·OCR 파이프라인
│       │   ├── ByteTracker.kt     # IoU 기반 멀티 트래커
│       │   ├── BusInputActivity.kt# 버스 번호 입력 (STT / 키보드)
│       │   ├── HistoryActivity.kt # 탐지 기록 & 차트
│       │   ├── SettingsActivity.kt# 설정·보호자 연동
│       │   ├── AuthActivity.kt    # 로그인·회원가입
│       │   └── network/
│       │       └── ApiService.kt  # Retrofit API 정의
│       └── assets/
│           ├── BusProject_v11n_best_int8_manual.tflite
│           └── labels.txt
├── backend/                       # Spring Boot 백엔드
├── ai/                            # 모델 학습 관련
└── Disabled_backup_backend/       # 구 백엔드 백업 (비활성)
```

---

## Getting Started

### 요구 사항

- Android Studio Hedgehog 이상
- Android SDK 24 (Android 7.0) 이상
- 실기기 권장 (에뮬레이터는 카메라 제한)

### 빌드

```bash
git clone https://github.com/your-org/ondevice-ai-vision-app.git
cd ondevice-ai-vision-app/mobile
./gradlew assembleDebug
```

### 권한

앱 실행 시 아래 권한을 요청합니다.

- `CAMERA` — 실시간 객체 탐지
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — 탐지 기록 GPS 좌표 저장
- `RECORD_AUDIO` — 음성 버스 번호 입력 (STT)

---

## ByteTracker 동작 원리

프레임 간 탐지가 1~2프레임 끊겨도 박스가 사라지지 않도록 3단계 IoU 매칭을 수행합니다.

```
Stage 1  활성 트랙  ↔  고신뢰 탐지  (IoU ≥ 0.30)
Stage 2  잔여 트랙  ↔  저신뢰 탐지  (IoU ≥ 0.40)
Stage 3  소실 트랙  ↔  매칭 안 된 고신뢰 탐지  (재식별)
```

- **EMA 스무딩** (`α = 0.7`): 박스 좌표를 지수이동평균으로 갱신해 떨림 완화
- **Carry-over**: 소실 후 3프레임까지 마지막 위치 유지
- **추가 연산 부담**: < 0.1ms (TFLite 추론 대비 무시 수준)

---

## Inference Pipeline 상세

### INT8 입력 양자화

```kotlin
// scale ≈ 1/255, zeroPoint = -128 일 때 빠른 경로 사용
val quantized = if (useFastInt8InputQuantization) {
    channelValue - 128
} else {
    (channelValue / 255f / inputScale + inputZeroPoint).roundToInt()
}
```

### 출력 역양자화

```kotlin
// INT8 출력 → float 좌표/스코어 복원
val value = (raw - outputZeroPoint) * outputScale
```

### OCR 트리거 조건

버스 bbox 면적이 화면의 **12% 이상**일 때만 ML Kit OCR를 호출합니다.  
동일 노선 번호가 **4.5초 내 2회 이상** 인식되면 앞문 안내를 시작합니다.

---

## License

MIT
