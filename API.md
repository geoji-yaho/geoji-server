# 떼거지 API 명세서

Base URL:
- 배포: `http://Geoji-server-env.eba-wpbw3dvr.ap-northeast-2.elasticbeanstalk.com`
- 로컬: `http://localhost:8080`

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

### `POST /api/me` — 온보딩 (S-01 로그인 직후 ~ S-02 월예산)

Supabase Auth 가입만으로는 `profiles`에 행이 생기지 않는다. **로그인 후 이 API를 한 번
호출해야** 방 생성/지출 기록 등 나머지 기능이 전부 동작한다 (다른 테이블이 전부
`profiles.id`를 참조하기 때문).

**Request**
```json
{ "nickname": "김규민", "monthlyBudget": 500000 }
```
`monthlyBudget`은 생략하면 500,000원(온보딩 화면 기본값)으로 채워진다.

**Response `201`** — `GET /api/me`와 같은 형식
**Response `409`** — 이미 온보딩을 마친 계정인 경우

### `PUT /api/me`

닉네임/월예산 수정. Request/Response 형식은 `POST /api/me`와 동일.

---

## 2. 거지방 (Room)

### `GET /api/rooms`

내가 멤버인 방 목록 (홈 화면 S-03, 방이 없으면 빈 배열).

**Response `200`** — `POST /api/rooms` 응답과 같은 형식의 배열

### `POST /api/rooms`

거지방을 만들고, 요청한 유저를 첫 멤버로 자동 가입시킨다.

**Request**
```json
{
  "name": "야근족 거지방",
  "spiceLevel": "SPICY",
  "voteDeadlineMinutes": 720,
  "rules": ["배달 24,000원? 밥이 있으면 라면으로 드셔야죠."]
}
```

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `name` | string | O | 1~20자 |
| `spiceLevel` | enum | O | `MILD`(순한맛) \| `SPICY`(매운맛) \| `HELL`(지옥맛) — AI팀 backend-contract.md D-21 표기에 맞춤 |
| `voteDeadlineMinutes` | int | O | `30` \| `60` \| `180` \| `360` \| `720` |
| `rules` | string[] | X | 최대 10개 (서버는 검증 안 함, DB 제약도 없음 — 클라이언트 책임) |

**Response `201`**
```json
{
  "id": "b3f1...",
  "name": "야근족 거지방",
  "spiceLevel": "SPICY",
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

**지출은 방 소속이 아니라 유저 소속이다.** 1탭 기록에는 방을 고르는 화면이 없다 — 기록하면
내가 속한 **모든 방**의 그리드에 같이 뜬다. 여러 방에 있는 사람이 하나 찍으면 그 방들 전부에
보여야 "친구들이 서로 감시한다"는 서비스 취지가 성립하기 때문. (예전엔 지출이 방 하나에
고정돼서 다른 방 친구들은 못 보는 버그가 있었음 — 수정됨.)

### `POST /api/expenses`

1탭 지출 기록. 인증된 유저 본인 명의로, 방 지정 없이 기록된다.

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
  "userId": "045e09df-8b88-4a12-96f1-3848c7232c87",
  "amount": 5500,
  "category": "카페",
  "memo": "아이스아메리카노",
  "source": "quick_tap",
  "spentAt": "2026-09-06T15:03:12+09:00"
}
```

### `GET /api/rooms/{roomId}/expenses?from={ISO datetime}&to={ISO datetime}`

홈 그리드(멤버 × 시간대)를 그리기 위한 기간 조회. **이 방의 멤버들**이 그 기간에 기록한 지출을
`spentAt` 내림차순으로 반환 — 지출 자체엔 방 정보가 없고, 요청 시점에 "누가 이 방 멤버인지"로
걸러진다.

**Query**
- `from`, `to` — 둘 다 필수, ISO 8601 (`2026-09-01T00:00:00+09:00` 형식)

**Response `200`**
```json
[
  {
    "id": "e7a2...",
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

## 4. 격자 칸 댓글 (조리돌림)

같은 지출이 여러 방에 동시에 보일 수 있으니, 댓글은 **어느 방 그리드에서 보고 달았는지**를
URL에 명시해야 한다. 같은 지출이라도 방마다 댓글 스레드가 분리된다 — A방 사람이 단 댓글이
B방에는 안 보임.

### `GET /api/rooms/{roomId}/expenses/{expenseId}/comments`

특정 방 맥락에서, 특정 지출 기록에 달린 댓글을 시간순으로 조회.

**Response `200`**
```json
[
  {
    "id": "c1a2...",
    "expenseId": "e7a2...",
    "roomId": "b3f1...",
    "userId": "6db45245-5518-40e8-92dc-7e8daf8e65fc",
    "content": "15시에 카페? 회의 있었나요",
    "createdAt": "2026-09-06T15:05:00+09:00"
  }
]
```

### `POST /api/rooms/{roomId}/expenses/{expenseId}/comments`

**Request**
```json
{ "content": "15시에 카페? 회의 있었나요" }
```
`content`는 1~500자.

**Response `201`** — 위와 같은 형식의 댓글 객체 1개
**Response `400`** — `expenseId`가 존재하지 않거나, 지출 작성자가 이 방 멤버가 아닌 경우
**Response `409`** — 댓글 작성자 본인이 이 방 멤버가 아닌 경우

실시간 반영은 이 API가 아니라 Supabase Realtime(`comments` 테이블 publication)이 담당한다.
클라이언트는 저장 후 이 API로 응답을 받고, 다른 멤버 화면은 Realtime 구독으로 갱신된다.

---

## 5. 왕관 (즉위/폐위)

### `GET /api/rooms/{roomId}/crown`

현재 재위 중인 왕 조회.

**Response `200`**
```json
{
  "id": "k1a2...",
  "roomId": "b3f1...",
  "userId": "045e09df-8b88-4a12-96f1-3848c7232c87",
  "startedAt": "2026-09-01T00:00:00+09:00",
  "endedAt": null,
  "dethronedByExpenseId": null
}
```
**Response `404`** — 아직 아무도 즉위하지 않은 방

### `GET /api/rooms/{roomId}/crown/history`

즉위/폐위 이력 전체를 `startedAt` 내림차순으로 조회. 응답 형식은 위와 같은 객체의 배열.

### `POST /api/rooms/{roomId}/crown`

즉위 처리. 현재 왕이 있으면 자동으로 먼저 폐위(`endedAt` 기록)시킨 뒤 새 재위를 연다 —
방 하나당 `endedAt`이 `null`인 행은 DB 부분 유니크 인덱스로 최대 1개만 허용되기 때문에
이 순서를 반드시 지켜야 한다. 이 엔드포인트가 그 순서를 대신 처리해준다.

**Request**
```json
{
  "userId": "045e09df-8b88-4a12-96f1-3848c7232c87",
  "dethronedByExpenseId": "e7a2..."
}
```
`dethronedByExpenseId`는 폐위 속보 카드에 쓸 근거용. 최초 즉위처럼 근거가 없으면 생략 가능.

**Response `201`** — 새로 생성된 재위 기록
**Response `409`** — 이미 재위 중인 유저를 다시 즉위시키려는 경우

이 엔드포인트는 "누가 왕이 되어야 하는가"는 판단하지 않는다 (주간 최저 지출자 계산 등은
호출하는 쪽의 책임). 여기서는 순서를 지켜 상태만 전환한다.

---

## 6. 주간 거지왕 시상식

패턴 탐지(통계)와 상 이름 발명(LLM)은 원래 이 API 밖에서 끝난 결과를 저장/조회하는 용도로
설계했다. 다만 AI 쪽 API 문서가 아직 없어서, 아래 `/generate`만 예외적으로 서버가 최소한의
통계(주간 최고 지출자)를 직접 계산하고 `AiClient`(현재는 `StubAiClient` — 고정 문구만 반환하는
자리 표시자)를 호출해 데모가 끝까지 돌아가게 해뒀다. **AI 팀 API가 나오면 `StubAiClient`를
실제 구현으로 교체하면 되고, 호출부는 안 바꿔도 된다.**

### `GET /api/rooms/{roomId}/awards?weekStart={YYYY-MM-DD}`

**Response `200`**
```json
[
  {
    "id": "aw1...",
    "roomId": "b3f1...",
    "weekStart": "2026-08-24",
    "weekEnd": "2026-08-30",
    "awardType": "invented",
    "title": "화요일에만 무너지는 사람상",
    "winnerUserId": "6db45245-5518-40e8-92dc-7e8daf8e65fc",
    "description": "월·수·목·금은 완벽합니다. 화요일만 3주 연속 붕괴했습니다.",
    "statsSnapshot": { "pattern": "weekday_bias", "weekday": "TUE", "streak": 3 },
    "createdAt": "2026-08-31T00:00:00+09:00"
  }
]
```

### `POST /api/rooms/{roomId}/awards`

**Request**
```json
{
  "weekStart": "2026-08-24",
  "weekEnd": "2026-08-30",
  "awardType": "invented",
  "title": "화요일에만 무너지는 사람상",
  "winnerUserId": "6db45245-5518-40e8-92dc-7e8daf8e65fc",
  "description": "월·수·목·금은 완벽합니다. 화요일만 3주 연속 붕괴했습니다.",
  "statsSnapshot": { "pattern": "weekday_bias", "weekday": "TUE", "streak": 3 }
}
```

| 필드 | 타입 | 필수 | 비고 |
|---|---|---|---|
| `weekStart`, `weekEnd` | date | O | |
| `awardType` | enum | O | `fixed_king` \| `fixed_spender` \| `invented` |
| `title` | string | O | 고정 상은 "거지왕"/"탕진왕", `invented`는 LLM이 지은 이름 |
| `winnerUserId` | uuid | X | |
| `description` | string | X | 수상평 |
| `statsSnapshot` | JSON(임의) | O | 상 발명 근거 통계. 그대로 저장했다가 그대로 돌려준다 |

**Response `201`** — 위 GET과 같은 형식
**Response `409`** — 같은 방·주·타입·제목 조합이 이미 있는 경우 (유니크 제약)

### `POST /api/rooms/{roomId}/awards/generate?weekStart={YYYY-MM-DD}&weekEnd={YYYY-MM-DD}`

MVP 설계서 13장 "시상식 즉시 생성 버튼"용 데모 엔드포인트. 통계·요청 바디 없이 그 주 지출만
있으면 바로 상 하나를 만들어준다.

**동작**
1. `roomId` + 기간의 지출을 유저별로 합산해 최고 지출자를 찾는다 (진짜 패턴 탐지는 아직 없음).
2. `AiClient.inventWeeklyAward(statsSummary)`를 호출해 상 이름/수상평을 받는다 —
   지금은 `StubAiClient`라 실제 분석 없이 고정 문구가 온다.
3. `awardType: invented`로 저장한다.

**Response `201`**
```json
{
  "id": "aw2...",
  "roomId": "b3f1...",
  "weekStart": "2026-08-24",
  "weekEnd": "2026-08-30",
  "awardType": "invented",
  "title": "이번 주의 수상한 지출상 (임시)",
  "winnerUserId": "045e09df-8b88-4a12-96f1-3848c7232c87",
  "description": "AI 연동 전 데모용 문구입니다. ... 입력 통계: 이번 주 최고 지출자 userId=..., 합계=187000원 (전체 지출 12건)",
  "statsSnapshot": { "totalsByUser": { "045e09df-...": 187000 }, "expenseCount": 12 },
  "createdAt": "2026-09-07T00:00:00+09:00"
}
```
**Response `409`** — 그 기간에 지출 기록이 하나도 없는 경우

---

## 7. 개인 맞춤 도전 과제

절감 항목 계산과 서술 생성은 이 API 밖에서 끝난 결과를 저장/조회/수락하는 용도다.
(`AiClient.writeChallengeNarrative`가 이미 준비돼 있지만, 시상식과 달리 `/generate` 같은
전용 엔드포인트는 아직 안 붙였다 — 필요해지면 6장의 `/generate` 패턴 그대로 추가하면 된다.)

### `GET /api/rooms/{roomId}/challenges/me?weekStart={YYYY-MM-DD}`

내 이번 주 도전 과제 조회.

**Response `200`**
```json
{
  "id": "ch1...",
  "roomId": "b3f1...",
  "userId": "045e09df-8b88-4a12-96f1-3848c7232c87",
  "weekStart": "2026-09-01",
  "items": [
    { "label": "화·목요일 4회만 참으면", "savedAmount": 23000 },
    { "label": "금요일 배달 1회 포기", "savedAmount": 19000 }
  ],
  "narrative": "화·목 카페 4번만 참으면 왕이 됩니다.",
  "targetAmount": 4600,
  "status": "pending",
  "acceptedAt": null,
  "resolvedAt": null,
  "createdAt": "2026-09-01T00:00:00+09:00"
}
```
**Response `404`** — 이번 주 도전 과제가 아직 없는 경우

### `POST /api/rooms/{roomId}/challenges`

**Request**
```json
{
  "weekStart": "2026-09-01",
  "items": [
    { "label": "화·목요일 4회만 참으면", "savedAmount": 23000 }
  ],
  "narrative": "화·목 카페 4번만 참으면 왕이 됩니다.",
  "targetAmount": 4600
}
```
생성 시 유저는 토큰의 `sub`(=요청자 본인)로 고정된다. `status`는 항상 `pending`으로 시작.

**Response `201`** — 위 GET과 같은 형식
**Response `409`** — 같은 방·유저·주가 이미 있는 경우 (유니크 제약)

### `POST /api/rooms/{roomId}/challenges/{challengeId}/accept`

도전 수락. `status`를 `accepted`로, `acceptedAt`을 현재 시각으로 바꾼다.

**Response `200`** — 갱신된 도전 과제
**Response `400`** — 존재하지 않는 `challengeId`
**Response `409`** — 본인 것이 아닌 도전 과제를 수락하려는 경우

---

## 8. 개인화 순찰 알림

위험 시각 계산(통계)과 안내 문구 생성(LLM)은 이 API 밖에서 끝난 결과를 저장/조회/응답하는 용도다.
(`AiClient.writePatrolRiskReason`도 준비는 돼 있지만 아직 전용 엔드포인트는 없다.)

### `GET /api/rooms/{roomId}/patrol-notifications/me`

내 알림 목록을 `scheduledAt` 내림차순으로 조회.

**Response `200`**
```json
[
  {
    "id": "pn1...",
    "roomId": "b3f1...",
    "userId": "045e09df-8b88-4a12-96f1-3848c7232c87",
    "scheduledAt": "2026-09-06T15:00:00+09:00",
    "sentAt": "2026-09-06T15:00:03+09:00",
    "riskReason": "님은 오후 3시가 위험합니다.",
    "response": null,
    "respondedAt": null
  }
]
```

### `POST /api/rooms/{roomId}/patrol-notifications`

알림 스케줄 등록 (알림을 실제로 발송하는 것은 이 API의 역할이 아님).

**Request**
```json
{ "scheduledAt": "2026-09-06T15:00:00+09:00", "riskReason": "님은 오후 3시가 위험합니다." }
```

**Response `201`** — 위 GET 항목과 같은 형식 (요청자 본인 명의로 생성됨)

### `POST /api/rooms/{roomId}/patrol-notifications/{notificationId}/respond`

알림에 응답(지출함/안 함/무시). `respondedAt`이 채워진다.

**Request**
```json
{ "response": "no_spend" }
```
`response`: `spent` \| `no_spend` \| `ignored`

**Response `200`** — 갱신된 알림
**Response `400`** — 존재하지 않는 `notificationId`
**Response `409`** — 본인에게 온 알림이 아닌 경우

---

## 9. 하루로그

격자 전체를 읽고 서사를 만드는 것은 이 API 밖의 일이다. 결과를 저장/조회만 한다.

### `GET /api/rooms/{roomId}/daily-logs?date={YYYY-MM-DD}`

**Response `200`**
```json
{
  "id": "dl1...",
  "roomId": "b3f1...",
  "logDate": "2026-09-05",
  "summary": "오늘은 전원 무지출입니다.",
  "collapseTime": null,
  "mvpUserId": "045e09df-8b88-4a12-96f1-3848c7232c87",
  "createdAt": "2026-09-06T00:00:00+09:00"
}
```
**Response `404`** — 해당 날짜 로그가 아직 없는 경우

### `POST /api/rooms/{roomId}/daily-logs`

**Request**
```json
{
  "logDate": "2026-09-05",
  "summary": "오늘은 전원 무지출입니다.",
  "collapseTime": null,
  "mvpUserId": "045e09df-8b88-4a12-96f1-3848c7232c87"
}
```

**Response `201`** — 위 GET과 같은 형식
**Response `409`** — 같은 방·날짜 로그가 이미 있는 경우 (유니크 제약)

---

## 10. 방 멤버 (랭킹)

### `GET /api/rooms/{roomId}/members`

방 멤버 목록을 거지력(`debtScore`) 높은 순으로 정렬해 반환. 아직 배치가 안 돌아
`debtScore`가 없는 멤버는 맨 뒤로 밀린다.

**Response `200`**
```json
[
  {
    "userId": "045e09df-8b88-4a12-96f1-3848c7232c87",
    "nickname": "김규민",
    "avatarUrl": null,
    "debtScore": 71,
    "joinedAt": "2026-09-01T00:00:00+09:00"
  },
  {
    "userId": "6db45245-5518-40e8-92dc-7e8daf8e65fc",
    "nickname": "이소윤",
    "avatarUrl": null,
    "debtScore": null,
    "joinedAt": "2026-09-02T00:00:00+09:00"
  }
]
```

`debtScore`(거지력) 계산 로직 자체는 여기 없다 — 별도 배치가 채워 넣는 값을 그대로 보여줄 뿐이다.

---

## 11. 지출 재판 (유죄/무죄 투표 → AI 판결 → 형 집행)

와이어프레임 흐름 B: 지출 등록 → 방 피드 카드에서 유죄/무죄 투표 → 마감(또는 즉시 판결
버튼) 시 AI가 판결문·형량을 정함 → 유죄면 무지출 형 집행. **`quick_tap`("돈 썼어요")
지출만 재판 대상이다 — `purchase_check`("살까 말까")는 대상이 아니다.**

재판은 별도로 "여는" API가 없다 — 그 방·지출 조합으로 처음 조회하거나 투표하는 순간
자동 생성된다. 마감 시한은 새로 받지 않고 **그 방의 `voteDeadlineMinutes`**(방 생성 시
정한 값)를 그대로 써서, 생성 시점 + `voteDeadlineMinutes`로 계산한다.

투표는 **방 멤버 전원**이 할 수 있다 (배심원단을 따로 뽑지 않음). 단, 지출 작성자 본인은
투표할 수 없고, 한 사람당 한 표만 가능하다.

AI 판결문·형량 생성은 아직 `StubAiClient`다 (6장 시상식과 같은 자리 표시자 패턴) —
AI 팀 API가 나오면 `AiClient.judge(...)` 구현만 교체하면 된다.

### `GET /api/rooms/{roomId}/expenses/{expenseId}/trial`

재판 현황 조회 (없으면 이 호출로 자동 생성됨). 방 멤버만 조회 가능.

**Response `200`**
```json
{
  "id": "tr1...",
  "roomId": "b3f1...",
  "expenseId": "e7a2...",
  "votingDeadline": "2026-09-06T21:03:12+09:00",
  "verdict": null,
  "verdictText": null,
  "sentenceDays": null,
  "sentenceStartedAt": null,
  "sentenceEndedAt": null,
  "judgedAt": null,
  "guiltyVotes": 3,
  "notGuiltyVotes": 1,
  "myVote": "GUILTY",
  "votes": [
    { "id": "v1...", "voterUserId": "6db45245-5518-40e8-92dc-7e8daf8e65fc", "verdict": "GUILTY", "reason": "밥이 없으면 라면을 드셨어야죠.", "createdAt": "2026-09-06T15:10:00+09:00" }
  ]
}
```
`myVote`는 요청자 본인이 아직 투표하지 않았으면 `null`.

**Response `400`** — `expenseId`가 없거나, `purchase_check` 지출이거나, 지출 작성자가
이 방 멤버가 아닌 경우
**Response `409`** — 요청자 본인이 이 방 멤버가 아닌 경우

### `POST /api/rooms/{roomId}/expenses/{expenseId}/votes`

유죄/무죄 투표. S-14 화면에서 호출.

**Request**
```json
{ "verdict": "GUILTY", "reason": "밥이 없으면 라면을 드셨어야죠." }
```
`verdict`: `GUILTY` \| `NOT_GUILTY`, `reason`은 1~500자 필수. (`APPROVED`/`REJECTED`는
"살까 말까" 구매 동의/기각용으로 DB enum에는 있지만 이 엔드포인트는 아직 안 받는다 —
지출 재판은 `GUILTY`/`NOT_GUILTY`만 유효)

**Response `201`** — 갱신된 재판 현황 (위 `GET .../trial`과 같은 형식)
**Response `400`** — 존재하지 않는 지출
**Response `409`**
- 본인 지출에 투표하려는 경우 — `{ "message": "본인 지출에는 투표할 수 없습니다." }`
- 이미 투표한 경우 — `{ "message": "이미 투표했습니다." }`
- 마감된 경우 — `{ "message": "투표가 마감되었습니다." }`
- 이미 판결이 확정된 경우 — `{ "message": "이미 판결이 확정된 재판입니다." }`

### `POST /api/rooms/{roomId}/expenses/{expenseId}/trial/judge`

MVP 데모용 "즉시 판결" — 6장 시상식 `/generate`와 같은 패턴. 마감을 기다리지 않고
지금까지 모인 표로 바로 판결한다.

**동작**
1. 지금까지의 유죄/무죄 표를 집계한다 (동률이면 무죄).
2. `AiClient.judge(...)`를 호출해 판결문과 (유죄일 때만) 무지출 형 일수를 받는다.
3. 유죄면 `sentenceStartedAt`을 지금, `sentenceEndedAt`을 `sentenceStartedAt + sentenceDays`로 채운다.

**Response `200`** — 확정된 재판 (`verdict`, `verdictText`, 유죄면 `sentenceDays` 등이 채워짐)
**Response `400`** — 아직 투표가 시작되지 않은 재판(`GET .../trial`을 먼저 호출한 적이 없음)
**Response `409`** — 투표가 하나도 없거나, 이미 판결이 확정된 경우
