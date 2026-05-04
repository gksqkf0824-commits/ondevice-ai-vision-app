> **담당:** 김장섭, 박채은 (백엔드)  
> **기술 스택:** Spring Boot 3.2.5 · MySQL · Spring Security · JWT · WebSocket(STOMP)

---

## 📁 프로젝트 구조

```
backend/
└── src/main/java/com/example/demo/
    ├── config/
    │   ├── CorsConfig.java          # CORS 허용 설정
    │   ├── SecurityConfig.java      # Spring Security · JWT 필터 등록 · 권한별 접근 제어
    │   └── WebSocketConfig.java     # WebSocket(STOMP) 엔드포인트 및 메시지 브로커 설정
    │
    ├── controller/
    │   ├── UserController.java      # 회원가입 · 로그인 · 보호자 연결 API
    │   ├── ImageController.java     # 이미지 업로드 · 전체 기록 조회 API
    │   ├── RecordController.java    # 내 이용 기록 조회 API
    │   ├── HazardLogController.java # 위험 로그 저장 · 내 위험 로그 조회 API
    │   ├── DashboardController.java # 지자체용 위험 통계 · 목록 조회 API
    │   └── FileController.java      # 업로드된 이미지 파일 서빙
    │
    ├── entity/
    │   ├── User.java                # 유저 공통 엔티티 (추상 클래스)
    │   ├── PersonalUser.java        # 개인(시각장애인) 유저
    │   ├── GuardianUser.java        # 보호자 유저 (시각장애인과 연결 가능)
    │   ├── CompanyUser.java         # 기관 유저 (지자체 대시보드 접근 권한)
    │   ├── Record.java              # 이미지 업로드 및 탐지 결과 기록
    │   └── HazardLog.java           # 위험 객체 탐지 로그 (GPS 좌표 포함)
    │
    ├── filter/
    │   └── JwtFilter.java           # 요청마다 JWT 토큰 검증 및 인증 처리
    │
    ├── repository/
    │   ├── UserRepository.java
    │   ├── RecordRepository.java
    │   └── HazardLogRepository.java # 위치 기반 위험물 카운트 쿼리 포함
    │
    ├── service/
    │   ├── UserService.java         # 회원가입 · 로그인 · 보호자 조회
    │   ├── RecordService.java       # 기록 저장 · 조회
    │   ├── HazardLogService.java    # 위험 로그 저장 + 보호자 알림 트리거 판단
    │   └── HazardAlertService.java  # WebSocket으로 보호자에게 실시간 알림 전송
    │
    └── util/
        └── JwtUtil.java             # JWT 생성 · 파싱 · 유효성 검사

src/main/resources/
└── application.properties           # DB · JWT · 파일 업로드 설정
```

---

## ⚙️ 실행 전 설정

### 1. MySQL 데이터베이스 생성

```sql
CREATE DATABASE demo CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 2. application.properties 환경 맞게 수정

```properties
# DB 연결 정보 — 본인 환경에 맞게 수정
spring.datasource.url=jdbc:mysql://localhost:3306/demo?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
spring.datasource.username=root
spring.datasource.password=1234        # ← 본인 MySQL 비밀번호로 변경

# JWT 시크릿 (변경 금지 — 변경 시 기존 토큰 전부 무효화됨)
jwt.secret=boMyunAm-secret-key-2026-very-long-string-for-security
jwt.expiration=86400000                # 토큰 만료: 24시간(ms)

# 이미지 저장 경로
file.upload-dir=uploads

# 보호자 알림 임계값 (같은 위치에서 N번 이상 감지 시 알림)
hazard.alert-threshold=3
```

### 3. 서버 실행

```bash
cd backend
./mvnw spring-boot:run
# 또는 IntelliJ에서 DemoApplication.java 우클릭 → Run
```

서버 기본 포트: `http://localhost:8080`  
JPA `ddl-auto=update` 설정이므로 **테이블은 최초 실행 시 자동 생성**됩니다.

---

## 🔐 인증 방식

모든 API(로그인·회원가입 제외)는 **JWT Bearer 토큰** 필요합니다.

**로그인 후 받은 `token` 값을 아래처럼 헤더에 추가:**

```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

토큰 없이 보호된 API 호출 시 `401 Unauthorized` 반환됩니다.

---

## 👤 유저 타입 및 권한

| 타입             | role 값    | 설명                  | 접근 가능 API                               |
| ---------------- | ---------- | --------------------- | ------------------------------------------- |
| 개인(시각장애인) | `PERSONAL` | 앱 주 사용자          | 이미지 업로드, 내 기록 조회, 위험 로그 전송 |
| 보호자           | `GUARDIAN` | 시각장애인과 1:1 연결 | 시각장애인과 동일 + 실시간 알림 수신        |
| 기관             | `COMPANY`  | 지자체·복지기관       | 대시보드 API (`/api/dashboard/**`)          |

---

## 📡 API 명세

### 1. 회원가입 · 로그인

#### `POST /api/user/signup/personal` — 개인 회원가입

**인증:** 불필요

```json
// Request Body
{
  "username": "user01",
  "password": "pass1234",
  "name": "홍길동"
}

// Response 200
{ "message": "개인 회원가입 완료" }

// Response 400 (아이디 중복)
{ "message": "이미 존재하는 아이디입니다." }
```

---

#### `POST /api/user/signup/guardian` — 보호자 회원가입

**인증:** 불필요

```json
// Request Body
{
  "username": "guardian01",
  "password": "pass1234",
  "name": "김보호"
}

// Response 200
{ "message": "보호자 회원가입 완료" }
```

---

#### `POST /api/user/signup/company` — 기관 회원가입

**인증:** 불필요

```json
// Request Body
{
  "username": "company01",
  "password": "pass1234",
  "companyName": "경기도청",
  "managerName": "이담당"
}

// Response 200
{ "message": "기관 회원가입 완료" }
```

---

#### `POST /api/user/login` — 로그인

**인증:** 불필요  
**Content-Type:** `application/x-www-form-urlencoded`

```
username=user01&password=pass1234
```

```json
// Response 200
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "role": "PERSONAL",
  "username": "user01"
}

// Response 401
{ "message": "아이디 또는 비밀번호가 틀렸습니다." }
```

> ⚠️ **안드로이드 팀:** 로그인 성공 후 `token` 값을 기기에 저장하고, 이후 모든 API 호출 시 헤더에 추가해 주세요.

---

#### `POST /api/user/guardian/link` — 보호자-시각장애인 연결

**인증:** 필요 (보호자 계정)  
**Params:** `personalUserId={연결할 개인 유저 ID}`

```json
// Response 200
{ "message": "보호자 연결 완료" }

// Response 400
{ "message": "보호자 계정만 사용 가능합니다." }

// Response 404
{ "message": "연결할 사용자를 찾을 수 없습니다." }
```

---

### 2. 이미지 업로드 · 기록 조회

#### `POST /api/image` — 이미지 업로드 + 탐지 결과 저장

**인증:** 필요  
**Content-Type:** `multipart/form-data`

| 파라미터     | 타입   | 필수 | 설명                      |
| ------------ | ------ | ---- | ------------------------- |
| `file`       | File   | ✅   | 캡처 이미지 파일          |
| `resultText` | String | ❌   | OCR·YOLO 탐지 결과 텍스트 |

```json
// Response 200
{
  "message": "업로드 성공",
  "imageUrl": "uploads/1746000000000_capture.jpg"
}

// Response 500
{ "message": "업로드 실패: ..." }
```

---

#### `GET /api/records/my` — 내 이용 기록 조회

**인증:** 필요

```json
// Response 200
[
  {
    "id": 1,
    "imageUrl": "uploads/1746000000000_capture.jpg",
    "resultText": "(TTS) 다음 버스가 정차하겠습니다.",
    "createdAt": "2026-05-04T14:30:00"
  }
]
```

---

### 3. 위험 로그 (핵심 기능 — 안드로이드 팀 주목)

#### `POST /api/hazard` — 위험 객체 탐지 로그 전송

**인증:** 필요  
**Content-Type:** `application/json`

안드로이드에서 YOLO가 위험 객체 탐지 시 **즉시 이 API로 전송**합니다.  
서버는 수신 후 동일 위치 30분 내 3회 이상이면 **보호자에게 자동 알림** 전송합니다.

```json
// Request Body
{
  "objectType": "kickboard",
  "latitude": 37.4913,
  "longitude": 127.0301,
  "confidence": 0.87
}
```

| 필드         | 타입   | 필수 | 설명                                         |
| ------------ | ------ | ---- | -------------------------------------------- |
| `objectType` | String | ✅   | `kickboard` / `bollard` / `bus` / `bus_door` |
| `latitude`   | double | ✅   | GPS 위도                                     |
| `longitude`  | double | ✅   | GPS 경도                                     |
| `confidence` | double | ❌   | YOLO 신뢰도 (0.0 ~ 1.0, 기본값 0.0)          |

```json
// Response 200
{
  "message": "위험 로그 저장 완료",
  "id": 42,
  "objectType": "kickboard",
  "detectedAt": "2026-05-04T14:35:22"
}

// Response 400 (필수 값 누락)
{ "message": "latitude와 longitude는 필수입니다." }
```

---

#### `GET /api/hazard/my` — 내 위험 로그 조회

**인증:** 필요

```json
// Response 200
[
  {
    "id": 42,
    "objectType": "kickboard",
    "latitude": 37.4913,
    "longitude": 127.0301,
    "confidence": 0.87,
    "detectedAt": "2026-05-04T14:35:22"
  }
]
```

---

### 4. 지자체 대시보드 (기관 계정 전용)

#### `GET /api/dashboard/hazards` — 전체 위험 로그 목록

**인증:** 필요 (COMPANY 계정만 접근 가능)

| 쿼리 파라미터 | 형식                  | 설명                 |
| ------------- | --------------------- | -------------------- |
| `start`       | `2026-04-01T00:00:00` | 시작일 (없으면 전체) |
| `end`         | `2026-04-30T23:59:59` | 종료일 (없으면 전체) |

```
예: GET /api/dashboard/hazards?start=2026-04-01T00:00:00&end=2026-04-30T23:59:59
```

```json
// Response 200
[
  {
    "id": 1,
    "objectType": "kickboard",
    "latitude": 37.4913,
    "longitude": 127.0301,
    "confidence": 0.87,
    "detectedAt": "2026-04-15T09:22:00"
  }
]
```

---

#### `GET /api/dashboard/stats` — 위험물 종류별 통계

**인증:** 필요 (COMPANY 계정만)

```json
// Response 200
{
  "kickboard": 42,
  "bollard": 18,
  "bus": 5,
  "bus_door": 3
}
```

---

### 5. 이미지 파일 직접 접근

#### `GET /uploads/{filename}` — 업로드된 이미지 조회

**인증:** 불필요

```
예: GET /uploads/1746000000000_capture.jpg
```

파일 확장자에 따라 `Content-Type` 자동 반환 (`.jpg` → `image/jpeg`, `.png` → `image/png` 등)

---

## 🔌 WebSocket — 보호자 실시간 알림

보호자 앱은 서버 시작 시 WebSocket 연결을 맺고 자신의 알림 채널을 구독합니다.  
시각장애인이 위험 로그를 전송할 때, 같은 위치에서 **30분 내 3회 이상** 감지되면 자동으로 알림이 전송됩니다.

### 연결 방법 (STOMP)

```
WebSocket 엔드포인트: ws://서버주소:8080/ws
```

### 알림 구독 채널

```
/user/queue/alert
```

### 수신 메시지 형식

```json
{
  "type": "HAZARD_ALERT",
  "objectType": "kickboard",
  "latitude": 37.4913,
  "longitude": 127.0301,
  "message": "[위험 알림] user01 사용자 근처에서 전동킥보드 이(가) 반복 감지되었습니다."
}
```

> **안드로이드 팀:** 보호자 앱은 로그인 후 STOMP 연결 → `/user/queue/alert` 구독 → 메시지 수신 시 푸시 알림 표시 흐름으로 구현해 주세요.

---

## 🗄️ DB 테이블 구조

JPA `ddl-auto=update`로 서버 실행 시 자동 생성됩니다.

```
user               ← 공통 유저 테이블 (id, username, password, role)
├── personal_user  ← 개인 유저 (name)
├── guardian_user  ← 보호자 (name, linked_user_id)
└── company_user   ← 기관 (company_name, manager_name)

records            ← 이미지 업로드 기록 (image_url, result_text, created_at, user_id)
hazard_logs        ← 위험 탐지 로그 (object_type, latitude, longitude, confidence, detected_at, user_id)
```

---

## 🚨 에러 코드 정리

| HTTP 코드 | 상황                                              |
| --------- | ------------------------------------------------- |
| `200`     | 정상                                              |
| `400`     | 요청 값 오류 (필수 파라미터 누락, 중복 아이디 등) |
| `401`     | 인증 실패 (토큰 없음, 토큰 만료, 비밀번호 불일치) |
| `404`     | 대상 없음 (사용자 ID 없음 등)                     |
| `500`     | 서버 내부 오류 (파일 저장 실패 등)                |

---

## 📋 안드로이드 팀 연동 체크리스트

- [ ] 로그인 API 호출 후 `token` 값 기기에 저장
- [ ] 모든 API 요청 헤더에 `Authorization: Bearer {token}` 추가
- [ ] YOLO 위험 객체 탐지 시 → `POST /api/hazard` 호출 (GPS 좌표 필수)
- [ ] 버스 탑승 완료 시 → `POST /api/image` 로 캡처 이미지 + resultText 전송
- [ ] 보호자 앱: 로그인 후 WebSocket `/ws` 연결 → `/user/queue/alert` 구독

---
