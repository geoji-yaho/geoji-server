# 떼거지 API 명세서

Base URL: `http://localhost:8080` (배포 후에는 실제 도메인으로 교체)

## 인증

`/actuator/health`를 제외한 모든 엔드포인트는 Supabase Auth가 발급한 JWT(access token)가 필요하다.

```
Authorization: Bearer <supabase access_token>
```

- 토큰 발급은 Supabase Auth가 담당한다 (이 서버에 로그인/회원가입 엔드포인트는 없음).
- 토큰의 `sub` 클레임이 곧 `profiles.id` (= `auth.users.id`)다. 서버는 이 값을 "현재 유저"로 취급한다.
- 토큰이 없거나 유효하지 않으면 `401 Unauthorized`.

## 공통 에러 형식

| 상황 | 상태 코드 | 바디 |
|---|---|---|
| 인증 실패 (토큰 없음/만료/서명 불일치) | 401 | - |
| 요청 값이 잘못됨 (`IllegalArgumentException`) | 400 | `{ "message": "..." }` |
| 요청 바디 검증 실패 (`@Valid`) | 400 | Spring 기본 에러 형식 |
| 상태 충돌 (`IllegalStateException`, 예: 프로필 없음) | 409 | `{ "message": "..." }` |
| 리소스 없음 | 404 | - |

---

## 1. 프로필

### `GET /api/me`

내 프로필 조회.

**Response `200`**
```json
{
  "id": "045e09df-8b88-4a12-96f1-3848c7232c87",
  "nickname": "김규민",
  "avatarUrl": null,
  "monthlyBudget": 500000
}
```

**Response `409`** — 아직 `profiles`에 행이 없는 경우 (온보딩 전)
```json
{ "message": "프로필이 없습니다. 온보딩을 먼저 완료해야 합니다." }
```

---

## 2. 거지방 (Room)

### `POST /api/rooms`

거지방을 만들고, 요청한 유저를 첫 멤버로 자동 가입시킨다.

**Request**
```json
{
  "name": "야근족 거지방",
  "spiceLevel": "hot",
  "voteDeadlineMinutes": 720,
  "rules": ["배달 24,000원? 밥이 있으면 라면으로 드셔야죠."]
}
```

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `name` | string | O | 1~20자 |
| `spiceLevel` | enum | O | `mild` \| `hot` \| `direct` |
| `voteDeadlineMinutes` | int | O | `30` \| `60` \| `180` \| `360` \| `720` |
| `rules` | string[] | X | 최대 10개 (서버는 검증 안 함, DB 제약도 없음 — 클라이언트 책임) |

**Response `201`**
```json
{
  "id": "b3f1...",
  "name": "야근족 거지방",
  "spiceLevel": "hot",
  "voteDeadlineMinutes": 720,
  "rules": ["배달 24,000원? 밥이 있으면 라면으로 드셔야죠."],
  "inviteCode": "a1b2c3d4",
  "createdBy": "045e09df-8b88-4a12-96f1-3848c7232c87"
}
```
`inviteCode`는 서버(DB 기본값)가 생성한다 — 요청에 포함해도 무시됨.

### `GET /api/rooms/{roomId}`

방 정보 조회.

**Response `200`** — `POST /api/rooms` 응답과 동일한 형식
**Response `404`** — 존재하지 않는 `roomId`

### `POST /api/rooms/join/{inviteCode}`

초대 코드로 방에 참가. 이미 멤버면 그대로 방 정보만 반환 (에러 아님).

**Response `200`** — 방 정보
**Response `400`**
```json
{ "message": "유효하지 않은 초대 코드입니다." }
```

---

## 3. 지출 기록 (Expense)

### `POST /api/rooms/{roomId}/expenses`

1탭 지출 기록. 인증된 유저 본인 명의로 기록된다.

**Request**
```json
{
  "amount": 5500,
  "category": "카페",
  "memo": "아이스아메리카노",
  "source": "quick_tap",
  "spentAt": null
}
```

| 필드 | 타입 | 필수 | 비고 |
|---|---|---|---|
| `amount` | int | O | 0 이상 |
| `category` | string | X | AI 자동 추론값이 들어갈 자리. 클라이언트가 직접 채워도 됨 |
| `memo` | string | X | |
| `source` | enum | O | `quick_tap` \| `purchase_check` |
| `spentAt` | ISO 8601 datetime | X | 생략 시 서버가 현재 시각으로 채움 |

**Response `201`**
```json
{
  "id": "e7a2...",
  "roomId": "b3f1...",
  "userId": "045e09df-8b88-4a12-96f1-3848c7232c87",
  "amount": 5500,
  "category": "카페",
  "memo": "아이스아메리카노",
  "source": "quick_tap",
  "spentAt": "2026-09-06T15:03:12+09:00"
}
```

### `GET /api/rooms/{roomId}/expenses?from={ISO datetime}&to={ISO datetime}`

홈 그리드(멤버 × 시간대)를 그리기 위한 기간 조회. `spentAt` 내림차순.

**Query**
- `from`, `to` — 둘 다 필수, ISO 8601 (`2026-09-01T00:00:00+09:00` 형식)

**Response `200`**
```json
[
  {
    "id": "e7a2...",
    "roomId": "b3f1...",
    "userId": "045e09df-8b88-4a12-96f1-3848c7232c87",
    "amount": 5500,
    "category": "카페",
    "memo": "아이스아메리카노",
    "source": "quick_tap",
    "spentAt": "2026-09-06T15:03:12+09:00"
  }
]
```

---

## 아직 없는 엔드포인트 (참고)

아래는 MVP 설계서 기능이지만 이번 스캐폴딩에는 포함하지 않았다. 필요할 때 요청하면 추가함.

- 격자 칸 댓글(조리돌림) 등록/조회 — `comments` 테이블은 있지만 컨트롤러 없음
- 왕관 즉위/폐위 조회 — `crown_history` 테이블은 있지만 컨트롤러 없음
- 주간 시상식 조회/생성 — `weekly_awards`
- 개인 도전 과제 조회/수락 — `challenges`
- 순찰 알림 조회/응답 — `patrol_notifications`
- 하루로그 — `daily_logs`
- 방 멤버 목록 조회 (거지력 랭킹용) — `room_members`
