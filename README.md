<h1 align="center">온길</h1>

<p align="center">
  시각 약자를 위한 온디바이스 실시간 AI 탐지·안내 Android 앱
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white" />
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white" />
  <img src="https://img.shields.io/badge/Model-YOLOv11n%20INT8-FF6F00?logo=tensorflow&logoColor=white" />
  <img src="https://img.shields.io/badge/Min%20SDK-API%2024-informational" />
  <img src="https://img.shields.io/badge/License-MIT-blue" />
</p>

---

스마트폰 카메라로 버스·장애물을 실시간 탐지하고 음성으로 탑승 위치를 안내하는 접근성 앱입니다.
YOLOv11n INT8 모델이 기기 내에서 완결되어 서버·네트워크 없이 동작합니다.

---

## Features

| | 기능 | 설명 |
|---|------|------|
| 탐지 | 실시간 객체 탐지 | YOLOv11n INT8 TFLite로 버스·버스문·킥보드·볼라드 탐지 |
| 탐지 | 버스 번호 OCR | ML Kit 한국어 OCR로 노선 번호 인식 (동일 번호 2회 확인 후 확정) |
| 안내 | 앞문 위치 안내 | 버스 번호 확정 후 앞문 위치 추정 및 TTS 음성 안내 (번호 미확정 시 시각 표시만) |
| 입력 | 음성 입력 (STT) | 탑승할 버스 번호를 음성으로 입력 |
| 트래킹 | IoU 기반 트래킹 | ByteTracker로 프레임 간 탐지 끊김 방지 및 박스 스무딩 |
| 기록 | 탐지 기록 | GPS 좌표와 함께 탐지 이력 저장 및 차트 시각화 |
| 연동 | 보호자 연동 | 보호자 계정 연동으로 피보호자 상황 공유 |

---

## Detection Classes

```
bus          버스 전체 영역
bus_door     버스 앞문
kickboard    전동 킥보드
bollard      볼라드 (차도 경계석)
```

---

## Architecture

```
카메라 프레임
     │
     ▼
[CameraX RGBA_8888]
     │  letterbox + INT8 양자화
     ▼
[YOLOv11n TFLite]  ── 온디바이스 추론 (CPU XNNPACK)
     │  NMS → keepOnlyPrimaryBus
     │
     ├─ bus  ──────────────────► [ByteTracker]  ── IoU 매칭 3단계 + EMA 스무딩
     │                                │
     │                                ▼
     │                    bus 탐지 & 면적 조건 충족
     │                                │
     │                                ▼
     │                       [ML Kit OCR]  ── 노선 번호 인식
     │                                │     타겟: 4.5초 내 2회 확인
     │                                │     일반: 8초 내 2회 확인
     │                                ▼
     │                    번호 확정 → 앞문 위치 추정 → TTS 안내
     │
     ├─ bus_door  ─────────────► raw detection (EMA 스무딩 + 500ms carry-over)
     │                           번호 확정 전: 오버레이 시각 표시만
     │                           번호 확정 후: TTS 앞문 방향 안내
     │
     └─ kickboard / bollard ──► raw detection → TTS 경고 안내
```

### ByteTracker

프레임 간 탐지가 최대 3프레임 끊겨도 박스가 유지되도록 3단계 IoU 매칭을 수행합니다.
`bus_door` · `bollard` · `kickboard`는 특성상 ByteTracker를 우회해 raw detection을 직접 사용합니다.

```
Stage 1  활성 트랙  ↔  고신뢰 탐지  (IoU ≥ 0.30)
Stage 2  잔여 트랙  ↔  저신뢰 탐지  (IoU ≥ 0.40)
Stage 3  소실 트랙  ↔  매칭 안 된 고신뢰 탐지  (재식별)
```

- **EMA 스무딩** (`α = 0.5`): 박스 좌표를 지수이동평균으로 갱신해 떨림 완화
- **Carry-over**: 소실 후 최대 3프레임까지 마지막 위치 유지
- **추가 연산**: < 0.1 ms (TFLite 추론 대비 무시 수준)

### INT8 Quantization

```kotlin
// 빠른 경로: scale ≈ 1/255, zeroPoint = -128
val quantized = if (useFastInt8InputQuantization) {
    channelValue - 128
} else {
    (channelValue / 255f / inputScale + inputZeroPoint).roundToInt()
}

// 출력 역양자화: INT8 → float
val value = (raw - outputZeroPoint) * outputScale
```

버스 bbox 면적이 화면의 **12% 이상**일 때만 ML Kit OCR를 호출합니다.
번호 확정 조건: **타겟 버스** — 4.5초 내 2회 이상 / **일반 버스** — 8초 내 2회 이상.
번호 확정 후에만 TTS로 앞문 방향을 안내합니다 (미확정 시 오버레이 시각 표시만).

---

## Tech Stack

**Mobile (Android)**

| 영역 | 라이브러리 |
|------|-----------|
| 언어 | Kotlin |
| 카메라 | CameraX 1.3.0 |
| 추론 | TensorFlow Lite 2.16.1 + GPU Delegate |
| OCR | Google ML Kit Text Recognition Korean 16.0.1 |
| 트래킹 | ByteTracker (custom Kotlin) |
| 로컬 DB | Room 2.6.1 |
| 위치 | Google Play Services Location |
| 차트 | MPAndroidChart |
| 네트워크 | Retrofit2 + Gson |

**AI Model**

| 항목 | 값 |
|------|----|
| 아키텍처 | YOLOv11n |
| 포맷 | TFLite INT8 (manual quantization) |
| 클래스 수 | 4 |
| 백엔드 | CPU XNNPACK |

---

## Project Structure

```
ondevice-ai-vision-app/
├── mobile/                        # Android 앱
│   └── app/src/main/
│       ├── java/com/example/ondevice/
│       │   ├── CameraActivity.kt  # 카메라·추론·OCR 파이프라인
│       │   ├── ByteTracker.kt     # IoU 기반 멀티 트래커
│       │   ├── BusInputActivity.kt
│       │   ├── HistoryActivity.kt
│       │   ├── SettingsActivity.kt
│       │   ├── AuthActivity.kt
│       │   └── network/ApiService.kt
│       └── assets/
│           ├── improved_model_320_full_int8.tflite
│           └── labels.txt
├── backend/                       # Spring Boot 백엔드
└── ai/                            # 모델 학습
```

---

## Getting Started

**요구 사항**
- Android Studio Hedgehog 이상
- Android SDK 24 (Android 7.0) 이상
- 실기기 권장 (에뮬레이터는 카메라 제한)

**빌드**

```bash
git clone https://github.com/your-org/ondevice-ai-vision-app.git
cd ondevice-ai-vision-app/mobile
./gradlew assembleDebug
```

**필요 권한**

| 권한 | 용도 |
|------|------|
| `CAMERA` | 실시간 객체 탐지 |
| `ACCESS_FINE_LOCATION` | 탐지 기록 GPS 저장 |
| `RECORD_AUDIO` | 음성 버스 번호 입력 (STT) |

---

## Team

| 이름 | 역할 | 주요 기여 |
|------|------|-----------|
| 권소윤 (팀장) | PM · AI · Mobile | AI 모델 학습, AI 추론 파이프라인, ByteTracker 구현, INT8 양자화, AI-모바일 통합 개발, 코드 통합 |
| 김연철 | AI | AI 모델 학습 및 성능 개선, 온디바이스 경량화 및 양자화 |
| 김장섭 | Backend | Spring Boot 서버 구축, REST API 구현 |
| 박채은 | Backend | MySQL DB 설계 및 구축, 코드 통합 |
| 최현웅 | Mobile | 요구사항 분석, 초기 와이어프레임 제작, 실기기 테스트 |
| 이주호 | Mobile | 초기 Android UX/UI 및 앱 로직 개발 |
| 오현민 | Docs | 논문 작성 및 최종 보고서 |

---

## License

MIT
