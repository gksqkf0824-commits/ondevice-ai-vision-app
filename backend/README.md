# 보면앎 Backend 진행 상황 정리

> 작성일: 2026-04-07
> 담당: 백엔드 (김장섭)

---

## ✅ 완료된 작업

### 1. Spring Boot 프로젝트 기초 세팅

- Spring Boot 3.2.5 / Java 17 / Maven
- MySQL 8.0 연동 완료
- JPA 자동 테이블 생성 (`ddl-auto=update`)

### 2. 사용자(User) 시스템 구축

- `User` 추상 엔티티 기반 상속 구조 설계
  - `PersonalUser` (개인 사용자) - name 필드 포함
  - `CompanyUser` (기업/기관 사용자) - companyName 필드 포함
- JPA `@Inheritance(strategy = JOINED)` 방식 적용
- 개인 회원가입 API: `POST /api/user/signup/personal`
- 기업 회원가입 API: `POST /api/user/signup/company`

### 3. 로그인 + JWT 인증 구현

- **BCrypt 비밀번호 암호화** 적용 (평문 저장 없음)
- **JWT 토큰 발급** 구현 (로그인 성공 시 토큰 반환)
- 로그인 API: `POST /api/user/login`
- 토큰 유효시간: 24시간 (86400000ms)

### 4. Spring Security 설정

- CSRF 비활성화 (모바일 앱 연동용)
- Stateless 세션 정책 적용 (JWT 기반)
- 인증 없이 접근 가능한 경로:
  - `POST /api/user/login`
  - `POST /api/user/signup/**`
- 그 외 모든 API는 JWT 토큰 필요

### 5. 기록(Record) 기초 구현

- 이미지 URL + 인식 결과 텍스트 + 생성시간 저장
- 기록 저장 API: `POST /api/image`
- 기록 조회 API: `GET /api/records`

---

## 📁 현재 프로젝트 구조

```
backend/
└── src/main/java/com/example/demo/
    ├── config/
    │   └── SecurityConfig.java       # Security + JWT 설정
    ├── controller/
    │   ├── UserController.java       # 회원가입, 로그인
    │   ├── ImageController.java      # 이미지 업로드, 기록 조회
    │   └── FileController.java       # 파일 서빙
    ├── entity/
    │   ├── User.java                 # 사용자 추상 엔티티
    │   ├── PersonalUser.java         # 개인 사용자
    │   ├── CompanyUser.java          # 기업 사용자
    │   └── Record.java              # 인식 기록
    ├── filter/
    │   └── JwtFilter.java            # JWT 검증 필터
    ├── repository/
    │   ├── UserRepository.java
    │   └── RecordRepository.java
    ├── service/
    │   ├── UserService.java
    │   └── RecordService.java
    └── util/
        └── JwtUtil.java              # JWT 생성/검증 유틸
```

---

## 🔌 현재 API 목록

| Method | URL                         | 인증 필요 | 설명                      |
| ------ | --------------------------- | --------- | ------------------------- |
| POST   | `/api/user/signup/personal` | ❌        | 개인 회원가입             |
| POST   | `/api/user/signup/company`  | ❌        | 기업 회원가입             |
| POST   | `/api/user/login`           | ❌        | 로그인 → JWT 토큰 반환    |
| POST   | `/api/image`                | ✅        | 이미지 업로드 + 기록 저장 |
| GET    | `/api/records`              | ✅        | 전체 기록 조회            |
| GET    | `/uploads/{filename}`       | ✅        | 이미지 파일 서빙          |

---

## 🔒 API 사용 방법 (토큰 필요한 경우)

로그인 후 받은 JWT 토큰을 모든 요청 헤더에 포함

```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

---

## 🚧 앞으로 해야 할 작업

### 우선순위 높음

- [ ] `Record`에 `userId` 연관관계 추가 (현재 누구의 기록인지 구분 불가)
- [ ] 사용자별 기록 조회 API (`GET /api/records/my`)
- [ ] 안드로이드 팀과 AI 인식 결과 연동 (현재 "테스트 결과입니다" 하드코딩)

### 우선순위 중간

- [ ] 위험 상황 이벤트 감지 및 저장
- [ ] WebSocket 기반 실시간 알림 (보호자 알림)
- [ ] 보호자 대시보드 API

### 우선순위 낮음

- [ ] 오프라인-온라인 비동기 동기화
- [ ] 기관 연계 모니터링 기능

---

## ⚙️ 로컬 실행 방법

1. MySQL 실행 확인
2. `application.properties`에서 DB 비밀번호 본인 것으로 수정
   ```properties
   spring.datasource.password=본인_비밀번호
   ```
3. MySQL Workbench에서 `demo` 스키마 생성 (최초 1회)
4. IntelliJ에서 `DemoApplication.java` 실행
5. 서버 주소: `http://localhost:8080`
