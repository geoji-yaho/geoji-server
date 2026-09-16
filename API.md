# 떼거지 API 명세서

Base URL:
- 배포(HTTPS, CloudFront): `https://d13dsunuwl4yud.cloudfront.net`
- 로컬: `http://localhost:8080`

GitHub Pages(HTTPS)에서 호출하려면 반드시 위 CloudFront 주소를 써야 한다. EB 도메인은 HTTP만 지원해 HTTPS 페이지에서 mixed-content로 막힌다(9/15).

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
`nickname`은 생략 가능 — **카카오 등 소셜 로그인이면 Supabase가 JWT에 넣어준 프로필
닉네임(`user_metadata.name`/`nickname`/`full_name` 중 먼저 있는 값)으로 자동 채워진다.**
프로필 사진(`avatarUrl`)도 같은 방식으로 자동 채워진다 (`user_metadata.avatar_url`/`picture`).
요청에 `nickname`을 직접 보내면 그게 우선한다. 소셜 메타데이터도 없고 요청에도 없으면(이메일
가입 등) `400`. `monthlyBudget`은 생략하면 500,000원(온보딩 화면 기본값)으로 채워진다.

**Response `201`** — `GET /api/me`와 같은 형식
**Response `400`** — 닉네임을 못 채운 경우(`{ "message": "닉네임을 입력해주세요." }`)
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
  "spiceLevel": "spicy",
  "voteDeadlineMinutes": 720,
  "rules": ["배달 24,000원? 밥이 있으면 라면으로 드셔야죠."]
}
```

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `name` | string | O | 1~20자 |
| `spiceLevel` | enum | O | `mild`(순한맛) \| `spicy`(매운맛) \| `hell`(지옥맛) — AI팀 backend-contract.md §15.2 D-21 확정(프론트 값이 표준) |
| `voteDeadlineMinutes` | int | O | `30` \| `60` \| `180` \| `360` \| `720` |
| `rules` | string[] | X | 최대 10개 (서버는 검증 안 함, DB 제약도 없음 — 클라이언트 책임) |

**Response `201`**
```json
{
  "id": "b3f1...",
  "name": "야근족 거지방",
  "spiceLevel": "spicy",
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

### `GET /api/rooms/invite/{inviteCode}`

초대장 화면(S-05) 미리보기 — **참가하기 전에** 어떤 방인지 보여주기 위한 조회. 아직 멤버가 아닌
사람도 호출할 수 있다(JWT는 필요). 초대 코드만 알고 `roomId`는 모르는 상태라 `GET /api/rooms/{roomId}`로는
대신할 수 없다. 방 안의 지출·댓글은 주지 않는다.

**Response `200`**
```json
{
  "id": "b3f1...",
  "name": "야근족 거지방",
  "spiceLevel": "spicy",
  "voteDeadlineMinutes": 720,
  "rules": ["배달 24,000원? 밥이 있으면 라면으로 드셔야죠."],
  "ownerNickname": "지민",
  "memberCount": 5,
  "alreadyMember": false
}
```

| 필드 | 내용 |
|---|---|
| `ownerNickname` | 방장 닉네임. 프로필이 없으면 `null` |
| `memberCount` | 현재 멤버 수 |
| `alreadyMember` | 요청자가 이미 이 방 멤버인지. `true`면 참가 버튼 대신 바로 방으로 보내면 된다 |

**Response `400`** — 없는 초대 코드이거나 삭제된 방. 참가와 같은 본문
```json
{ "message": "유효하지 않은 초대 코드입니다." }
```

### `POST /api/rooms/join/{inviteCode}`

초대 코드로 방에 참가. 이미 멤버면 그대로 방 정보만 반환 (에러 아님).

**Response `200`** — 방 정보
**Response `400`**
```json
{ "message": "유효하지 않은 초대 코드입니다." }
```

### `DELETE /api/rooms/{roomId}/members/me`

방 탈퇴. 방장이 나가도 방 자체는 삭제되지 않는다(방 삭제는 아래 별도 API).

**Response `204`** — 바디 없음
**Response `404`** — 이 방의 멤버가 아님(이미 탈퇴한 경우 포함)
```json
{ "message": "이 방의 멤버가 아닙니다." }
```

### `DELETE /api/rooms/{roomId}`

방 삭제. **방장만** 할 수 있다. 소프트 삭제라 `GET /api/rooms`(목록)·`GET /api/rooms/{roomId}`(단건 조회)·
`POST /api/rooms/join/{inviteCode}`(참가)에서 곧바로 숨겨진다. 판결·투표·댓글 기록(`votes`·`post_comments`)은
이 방을 참조하고 있어 지우지 않는다 — 방만 비활성화될 뿐, 과거에 이 방에서 나온 판결 기록은 게시물 쪽에서 그대로 남는다.

**Response `204`** — 바디 없음
**Response `403`** — 방장이 아님
```json
{ "message": "방장만 방을 삭제할 수 있습니다." }
```
**Response `404`** — 존재하지 않거나 이미 삭제된 방

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

## 5. 주간 거지왕 시상식

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

## 6. 개인 맞춤 도전 과제

절감 항목 계산과 서술 생성은 이 API 밖에서 끝난 결과를 저장/조회/수락하는 용도다.
(`AiClient.writeChallengeNarrative`가 이미 준비돼 있지만, 시상식과 달리 `/generate` 같은
전용 엔드포인트는 아직 안 붙였다 — 필요해지면 5장의 `/generate` 패턴 그대로 추가하면 된다.)

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

## 7. 개인화 순찰 알림

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

## 8. 하루로그

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

## 9. 방 멤버 (랭킹)

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

`debtScore`(거지력)는 **조회할 때 계산한다**. 배치가 채우던 `room_members.debt_score` 는 더 쓰지 않는다.

| 항목 | 규칙 |
|---|---|
| 예산 점수 (0~50) | `50 × (1 − 이번 달 지출 ÷ 기준선)`, 0~50 으로 자른다 |
| 기준선 | `월예산 × max(오늘 일자, 3) ÷ 이번 달 일수`. 달 초에 점수가 튀지 않게 3일 하한을 둔다 |
| 이번 달 지출 | 본인이 쓴 `spent` 게시물 `amountKrw` 합(KST 기준 이번 달, 삭제된 글 제외). `considering` 은 아직 쓴 돈이 아니라 세지 않는다 |
| 평결 점수 (0~10) | 확정된 유죄·무죄 중 `10 × (무죄 ÷ 전체)`. 판결받은 게시물이 없으면 중립 5 |
| `debtScore` | 위 둘의 합을 반올림. 월예산이 0(온보딩 전)이면 `null`("집계 전")이고 목록 맨 뒤로 간다 |

공식은 프론트 `shared/domain/score.ts` 와 같다. 무지출·참여 점수는 양쪽 다 아직 0 이다.

---

## 10. 지출 재판 (유죄/무죄 투표 → AI 판결 → 형 집행)

와이어프레임 흐름 B: 지출 등록 → 방 피드 카드에서 투표 → 마감(또는 즉시 판결 버튼) 시
판결. 지출 종류(`source`)에 따라 투표 값과 판결 규칙이 다르다(S-06·S-14).

| 지출 종류 | 투표 값 | 판결 | 형량 |
|---|---|---|---|
| `quick_tap`("돈 썼어요") | `guilty` \| `notGuilty` | 유죄 표가 더 많으면 `guilty`, 동률 포함 그 밖은 `notGuilty`. 표가 0개면 판결 불가(409) | 유죄면 AI가 무지출 형 일수를 정함 |
| `purchase_check`("살까 말까") | `agree`(구매 동의) \| `disagree`(기각) | 전체 표가 2표 미만이면 `dismissed`(각하). 그 밖은 동의가 기각보다 많으면 `agree`, 동률 포함 그 밖은 `disagree` | 없음. `sentenceDays`·`sentenceStartedAt`·`sentenceEndedAt` 은 늘 `null` |

재판은 별도로 "여는" API가 없다 — 그 방·지출 조합으로 처음 조회하거나 투표하는 순간
자동 생성된다. 마감 시한은 새로 받지 않고 **그 방의 `voteDeadlineMinutes`**(방 생성 시
정한 값)를 그대로 써서, 생성 시점 + `voteDeadlineMinutes`로 계산한다.

투표는 **방 멤버 전원**이 할 수 있다 (배심원단을 따로 뽑지 않음). 단, 지출 작성자 본인은
투표할 수 없고, 한 사람당 한 표만 가능하다.

AI 판결문·형량 생성은 아직 `StubAiClient`다 (5장 시상식과 같은 자리 표시자 패턴) —
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
  "agreeVotes": 0,
  "disagreeVotes": 0,
  "myVote": "guilty",
  "votes": [
    { "id": "v1...", "voterUserId": "6db45245-5518-40e8-92dc-7e8daf8e65fc", "verdict": "guilty", "reason": "밥이 없으면 라면을 드셨어야죠.", "createdAt": "2026-09-06T15:10:00+09:00" }
  ]
}
```
`myVote`는 요청자 본인이 아직 투표하지 않았으면 `null`. `guiltyVotes`·`notGuiltyVotes`는 돈 썼어요,
`agreeVotes`·`disagreeVotes`는 살까 말까 재판의 표 수이고 해당 없는 쪽은 늘 `0`이다.

**Response `400`** — `expenseId`가 없거나, 지출 작성자가 이 방 멤버가 아닌 경우
**Response `409`** — 요청자 본인이 이 방 멤버가 아닌 경우

### `POST /api/rooms/{roomId}/expenses/{expenseId}/votes`

배심원 투표. S-14 화면에서 호출.

**Request**
```json
{ "verdict": "guilty", "reason": "밥이 없으면 라면을 드셨어야죠." }
```
`verdict`: 돈 썼어요는 `guilty` \| `notGuilty`, 살까 말까는 `agree` \| `disagree`. `reason`은 1~500자 필수.
`dismissed`는 판결 결과일 뿐 투표 값이 아니다.

**Response `201`** — 갱신된 재판 현황 (위 `GET .../trial`과 같은 형식)
**Response `400`**
- 존재하지 않는 지출
- 지출 종류에 맞지 않는 `verdict` — `{ "message": "살까 말까는 agree 또는 disagree만 투표할 수 있습니다." }` 또는 `{ "message": "지출 재판은 guilty 또는 notGuilty만 투표할 수 있습니다." }`
**Response `409`**
- 본인 지출에 투표하려는 경우 — `{ "message": "본인 지출에는 투표할 수 없습니다." }`
- 이미 투표한 경우 — `{ "message": "이미 투표했습니다." }`
- 마감된 경우 — `{ "message": "투표가 마감되었습니다." }`
- 이미 판결이 확정된 경우 — `{ "message": "이미 판결이 확정된 재판입니다." }`

### `POST /api/rooms/{roomId}/expenses/{expenseId}/trial/judge`

MVP 데모용 "즉시 판결" — 5장 시상식 `/generate`와 같은 패턴. 마감을 기다리지 않고
지금까지 모인 표로 바로 판결한다.

**동작 — 돈 썼어요(`quick_tap`)**
1. 지금까지의 유죄/무죄 표를 집계한다 (동률이면 무죄).
2. `AiClient.judge(...)`를 호출해 판결문과 (유죄일 때만) 무지출 형 일수를 받는다.
3. 유죄면 `sentenceStartedAt`을 지금, `sentenceEndedAt`을 `sentenceStartedAt + sentenceDays`로 채운다.

**동작 — 살까 말까(`purchase_check`)**
1. 동의/기각 표를 집계한다. 전체 2표 미만이면 `dismissed`, 동의 > 기각이면 `agree`, 그 밖(동률 포함)은 `disagree`.
2. `AiClient.judgePurchase(...)`로 판결문만 받는다. 형량 필드는 채우지 않는다.
3. 표가 0개여도 409가 아니라 `dismissed`로 확정된다.

**Response `200`** — 확정된 재판 (`verdict`, `verdictText`, 돈 썼어요 유죄면 `sentenceDays` 등이 채워짐)
**Response `400`** — 아직 투표가 시작되지 않은 재판(`GET .../trial`을 먼저 호출한 적이 없음)
**Response `409`** — 이미 판결이 확정된 경우, 또는 돈 썼어요 재판에 투표가 하나도 없는 경우

## 11. 게시물 등록(제출)

재판 흐름의 게시물(`posts`)을 등록한다. 3장 `expenses`와 별개다. 등록 직전에 AI 심문관이 입력을
한 번 보고(`PASS`·`NEEDS_CLARIFICATION`·`BLOCKED`), 질문이 나오면 사용자는 고치거나(`REVISE`) 그대로
등록한다(`PROCEED`). 등록되면 게시물이 공유 방들에 올라가고 투표 마감이 정해진다.

**상태 기계**

```
POST /api/post-submissions
  ├─ PASS                 → COMPLETED (postId)
  ├─ NEEDS_CLARIFICATION  → NEEDS_INPUT ── complete PROCEED ───────────────→ COMPLETED
  │                                     └─ complete REVISE → FINAL_CHECK ─┬ PASS    → COMPLETED
  │                                                                       └ BLOCKED → BLOCKED
  └─ BLOCKED              → BLOCKED ───── complete REVISE → FINAL_CHECK(위와 같음)
```

- 질문(`NEEDS_INPUT`)은 제출당 한 번만 나온다. `FINAL_CHECK`는 질문을 내지 않는다
- `REVISE`는 제출당 한 번. `FINAL_CHECK`에서 `BLOCKED`가 나오면 더 고칠 수 없다
- `BLOCKED`는 `PROCEED`로 등록할 수 없다(409)
- AI API가 느리거나(5초) 꺼져 있어도 등록은 막히지 않는다. 이때 `intakeResult.intakeSource`는 `FALLBACK`,
  `status`는 `PASS`
- 투표 마감(게시물 `voteDeadlineAt`) = 등록 시각 + 공유 방 투표 마감 분 중 가장 짧은 값

### `POST /api/post-submissions`

**Request**
```json
{
  "postType": "spent",
  "amountKrw": 4800,
  "category": "카페/간식",
  "item": "아이스 아메리카노",
  "reason": "야근해서",
  "roomIds": ["6a1f0c2e-3b7d-4c55-9d7e-2f1b8c0a9e41"]
}
```

| 필드 | 규칙 |
|---|---|
| `postType` | `spent` \| `considering` |
| `amountKrw` | 양의 정수 |
| `category` | `식비` `배달` `카페/간식` `교통/택시` `쇼핑/패션` `뷰티` `취미/여가` `술/유흥` `구독` `생활` `기타` 중 하나 |
| `item` | 앞뒤 공백 제거 뒤 1~30자 |
| `reason` | 선택. 앞뒤 공백 제거 뒤 200자 이하, 비면 `null` |
| `roomIds` | 1개 이상. 요청자가 멤버인 방만 |

**Response `201`**
```json
{
  "submissionId": "0f5c8a52-6a0e-4a8e-9a47-1c3f2d7e8b10",
  "status": "NEEDS_INPUT",
  "revision": "3b1f…(sha256 hex)",
  "intakeResult": {
    "schemaVersion": 1,
    "mode": "INITIAL",
    "status": "NEEDS_CLARIFICATION",
    "itemReview": { "status": "VAGUE", "suggestedItem": "커피 한 잔" },
    "message": "무엇을 샀는지 조금 더 알려주세요",
    "categoryReview": { "status": "OK", "suggestedCategory": null, "confidence": 0.9 },
    "injectionDetected": false,
    "intakeSource": "AI"
  },
  "postId": null
}
```

- `status`: `COMPLETED` \| `NEEDS_INPUT` \| `BLOCKED`. `COMPLETED`면 `postId`가 채워진다
- `revision`: 다음 `complete` 요청에 그대로 보낸다
- `intakeResult`: 심문관 결과. 질문 문구는 `message`, 제안은 `itemReview.suggestedItem`·`categoryReview.suggestedCategory`

### `POST /api/post-submissions/{submissionId}/complete`

질문(`NEEDS_INPUT`)이나 차단(`BLOCKED`) 뒤에 부른다.

**Request**
```json
{
  "action": "REVISE",
  "revision": "3b1f…(직전 응답의 revision)",
  "postType": "spent",
  "amountKrw": 4800,
  "category": "카페/간식",
  "item": "스타벅스 아이스 아메리카노",
  "reason": "야근해서",
  "roomIds": ["6a1f0c2e-3b7d-4c55-9d7e-2f1b8c0a9e41"]
}
```

- `action`: `REVISE`(고친 값으로 한 번 더 검토) \| `PROCEED`(질문을 보고 그대로 등록)
- 최종 값 전체를 보낸다. 검증 규칙은 위 표와 같다. `PROCEED`는 직전에 검토한 값과 같아야 한다

**Response `200`** — 위와 같은 형식. `REVISE`의 `intakeResult.mode`는 `FINAL_CHECK`

- 이미 등록된 제출에 다시 보내면 `action`과 상관없이 기존 `postId`로 `COMPLETED`를 돌려준다(게시물은 하나)

### 오류

| 상황 | 상태 코드 | 바디 |
|---|---|---|
| 입력 규칙 위반(`item` 길이, `reason` 길이, `amountKrw`, `category`, 빈 `roomIds`, `action` 없음) | 400 | `{ "message": "..." }` |
| `postType`·`action` 값이 enum 밖이거나 JSON 이 깨짐 | 400 | Spring 기본 에러 형식 |
| 요청자가 멤버가 아닌 방이 `roomIds`에 있음 | 403 | Spring 기본 에러 형식 |
| 없는 제출이거나 남의 제출 | 404 | Spring 기본 에러 형식 |
| `revision`이 현재 값과 다름 | 409 | `{ "message": "제출 내용이 바뀌었습니다. 다시 불러와 주세요." }` |
| `BLOCKED`를 `PROCEED` | 409 | `{ "message": "차단된 제출은 그대로 등록할 수 없습니다." }` |
| `PROCEED`인데 값이 검토한 값과 다름 | 409 | `{ "message": "검토한 값과 다릅니다. 고친 값은 REVISE 로 보내 주세요." }` |
| 이미 한 번 `REVISE`함 | 409 | `{ "message": "이미 한 번 고쳤습니다." }` |
| 지금 상태에서 할 수 없는 동작 | 409 | `{ "message": "지금은 완료할 수 없는 제출입니다." }` 또는 `"지금은 고칠 수 없는 제출입니다."` |

## 12. 게시물 재판(투표·판결 조회·공유 카드·삭제)

11장에서 등록한 게시물(`postId`)의 재판이다. 10장 `expenses` 재판과 테이블·경로가 다르다.

흐름: 공유 방 멤버가 투표 → 투표 가능 인원 전원이 투표하거나 마감(`voteDeadlineAt`)이 지나면 평결 확정 →
AI 판사가 형량·판결문을 만든다(최대 약 40초, 늦으면 템플릿 문구) → 프론트는 판결 조회를 폴링한다.

> **재판은 방마다 따로 한다(9/16).** 지출 기록만 여러 방에 공유되고, 투표·평결·판결문·댓글은 방 안에서 끝난다.
> A 방이 유죄라고 해서 B 방도 유죄가 되지 않고, 정족수도 그 방 멤버만 센다. 두 방에 다 있는 사람은
> **방마다 한 표씩** 낸다. 그래서 판결 관련 엔드포인트는 `room_id` 를 받는다 — 없으면 방별 재판 이전에
> 만들어진 옛 합산 판결만 보인다.

- 게시물을 볼 수 있는 사람: **작성자** 또는 **공유가 철회되지 않은 공유 방의 멤버**
- 게시물이 없거나, 삭제됐거나, 볼 수 없는 사람이면 `404 {"message"}`(셋을 구분하지 않는다)
- 요청·응답 JSON 은 camelCase. 값이 없는 필드도 키는 `null` 로 온다
- 전달은 폴링만 한다. Realtime·SSE 는 없다

### `GET /api/posts/{postId}?room_id={roomId}`

게시물 한 건. **`room_id` 를 넘긴다** — 판결·집계·투표 가능 인원이 모두 그 방 기준이다.
없으면 집계가 0 이고 옛 합산 판결만 보인다. 투표 화면(S-14)과 판결 화면(S-10)의 사건 개요·배심원 집계가 이것을 쓴다.
판결문·형량·짤은 여기 없다. 그것은 `GET /api/posts/{postId}/verdict` 가 준다.

**Response `200`**
```json
{
  "id": "3c9a1e7b-5d2f-4b8a-9e6c-1a2b3c4d5e6f",
  "postType": "spent",
  "amountKrw": 23000,
  "category": "교통/택시",
  "item": "심야 택시",
  "reason": "막차 놓쳐서",
  "authorId": "1a2b…",
  "authorNickname": "규민",
  "voteDeadlineAt": "2026-09-15T12:33:00Z",
  "createdAt": "2026-09-15T12:03:00Z",
  "rooms": [{ "id": "6a1f…", "name": "야근족 거지방", "spiceLevel": "spicy" }],
  "juryStatus": "guilty",
  "tally": { "oppose": 2, "support": 1 },
  "votes": [
    { "id": "0b8e…", "voterId": "9d1f…", "voterNickname": "지민",
      "verdict": "guilty", "reason": "지하철이 있었잖아요", "createdAt": "2026-09-15T12:10:00Z" }
  ],
  "myVote": null,
  "canVote": false,
  "eligibleVoterCount": 3
}
```

| 필드 | 내용 |
|---|---|
| `rooms` | 공유가 철회되지 않은 방만. 삭제된 방은 빠진다 |
| `juryStatus` | 평결. 아직 투표 중이면 `null` |
| `tally.oppose` | 유죄·기각 표 수. `tally.support` 는 무죄·동의 표 수 |
| `votes` | **볼 수 있는 표만.** 작성자는 전부, 그 밖에는 자기가 멤버인 방에서 나온 표만 본다(댓글과 같은 규칙) |
| `votes[].reason` | **평결 확정 전에는 `null`**. 확정 전에 남의 사유가 보이면 표가 쏠린다 |
| `tally` | **그 방 집계.** 다른 방 표는 들어가지 않는다 |
| `eligibleVoterCount` | **그 방** 멤버에서 작성자를 뺀 수 |
| `myVote` | 내 표. 아직 안 했으면 `null` |
| `canVote` | 작성자가 아니고, 아직 투표하지 않았고, 평결이 확정되지 않았으면 `true` |
| `eligibleVoterCount` | 투표 가능 인원(철회되지 않은 공유 방 멤버 합집합 − 작성자) |

**오류**

| 상황 | 상태 코드 | 바디 |
|---|---|---|
| 게시물 없음·삭제됨·볼 수 없는 사람 | 404 | `{ "message": "게시물을 찾을 수 없습니다." }` |
| JWT 없음 | 401 | - |

### `GET /api/rooms/{roomId}/posts`

방 피드(S-06). 최신 글이 위고 최대 100건이다. 철회된 공유와 삭제된 글은 빠진다.

**Response `200`**
```json
[
  {
    "id": "3c9a1e7b-5d2f-4b8a-9e6c-1a2b3c4d5e6f",
    "postType": "spent",
    "amountKrw": 23000,
    "category": "교통/택시",
    "item": "심야 택시",
    "authorId": "1a2b…",
    "authorNickname": "규민",
    "voteDeadlineAt": "2026-09-15T12:33:00Z",
    "createdAt": "2026-09-15T12:03:00Z",
    "juryStatus": null,
    "tally": { "oppose": 1, "support": 0 },
    "voted": true
  }
]
```

- `voted` 는 요청자가 그 게시물에 투표했는지다

**오류**

| 상황 | 상태 코드 | 바디 |
|---|---|---|
| 방이 없거나 요청자가 멤버가 아님 | 404 | `{ "message": "방을 찾을 수 없습니다." }` |
| JWT 없음 | 401 | - |

### `POST /api/posts/{postId}/votes`

배심원 투표. S-14 화면에서 호출한다. 게시물당 1인 1표이고 수정할 수 없다. 작성자는 투표하지 않는다.

**Request**
```json
{
  "verdict": "guilty",
  "reason": "지하철이 있었잖아요",
  "roomId": "6a1f0c2e-3b7d-4c55-9d7e-2f1b8c0a9e41"
}
```

| 필드 | 규칙 |
|---|---|
| `verdict` | `spent` 게시물은 `guilty` \| `notGuilty`, `considering` 게시물은 `agree` \| `disagree` |
| (방) | 투표는 `roomId` 가 정하는 방의 재판에 들어간다. 두 방에 다 있으면 방마다 한 번씩 낼 수 있다 |
| `reason` | 필수. 1~500자, 공백만은 안 된다. 앞뒤 공백을 지우지 않고 그대로 저장한다 |
| `roomId` | 투표하는 방. 이 게시물이 공유된(철회되지 않은) 방이고 요청자가 그 방 멤버여야 한다 |

**Response `201`**
```json
{
  "id": "0b8e5c1a-2f4d-4a6b-8c3e-9d1f2a7b6c50",
  "postId": "3c9a1e7b-5d2f-4b8a-9e6c-1a2b3c4d5e6f",
  "roomId": "6a1f0c2e-3b7d-4c55-9d7e-2f1b8c0a9e41",
  "verdict": "guilty",
  "reason": "지하철이 있었잖아요",
  "createdAt": "2026-09-15T12:03:12.345678Z"
}
```

- 이 표로 투표 가능 인원(공유 방 멤버 합집합 − 작성자) 전원이 투표했으면 같은 요청 안에서 평결이 확정된다

**오류** — 아래 순서로 판정한다

| 상황 | 상태 코드 | 바디 |
|---|---|---|
| 게시물이 없거나 삭제됨 | 404 | `{ "message": "게시물을 찾을 수 없습니다." }` |
| 작성자가 투표 | 403 | `{ "message": "본인 게시물에는 투표할 수 없습니다." }` |
| `roomId` 가 없거나, 공유 방이 아니거나, 공유가 철회됐거나, 요청자가 그 방 멤버가 아님 | 403 | `{ "message": "이 방에서는 투표할 수 없습니다." }` |
| `verdict` 가 게시물 종류에 맞지 않음(`dismissed`·없음 포함) | 400 | `{ "message": "이 게시물에 맞지 않는 평결입니다." }` |
| `reason` 없음·빈 값·공백만 | 400 | `{ "message": "투표 사유를 입력해 주세요." }` |
| `reason` 500자 초과 | 400 | `{ "message": "투표 사유는 500자 이하여야 합니다." }` |
| 마감이 지났거나 평결이 이미 확정됨 | 409 | `{ "message": "투표가 마감되었습니다." }` |
| 이미 투표함 | 409 | `{ "message": "이미 투표했습니다." }` |
| JSON 이 깨졌거나 `roomId` 가 UUID 가 아님 | 400 | Spring 기본 에러 형식 |
| JWT 없음 | 401 | - |

### `GET /api/posts/{postId}/verdict?room_id={roomId}`

판결 조회(폴링용). 스키마는 AI 저장소 `contracts/verdict-view-v1.schema.json` 의 키를 camelCase 로 바꾼 것이다.

- `room_id`(선택): 이 방의 강도(`mild`·`spicy`·`hell`) 문구를 본다. 철회되지 않은 공유 방이어야 하고, 작성자가 아니면 요청자가 그 방 멤버여야 한다.
  없으면 대표 강도(표가 가장 많이 나온 방의 강도)
- 생성 중에도 `200` 이고 `view` 가 `null` 이다. 빈 화면 대신 대기 메시지를 보여 준다

**Response `200`**
```json
{
  "schemaVersion": 1,
  "postId": "3c9a1e7b-5d2f-4b8a-9e6c-1a2b3c4d5e6f",
  "juryStatus": "guilty",
  "sentenceStatus": "FINAL",
  "textStatus": "AI_READY",
  "textVersion": 2,
  "view": {
    "intensity": "mild",
    "headline": "택시비 유죄",
    "statement": ["배심원 3인 중 3인이 유죄로 판단했습니다.", "지하철이 있었습니다."],
    "sentence": "oneDay",
    "sentenceLabel": "징역 1일 (내일 하루 무지출)",
    "sentencingReason": "같은 달 택시가 세 번째입니다.",
    "source": "AI",
    "meme": {
      "tag": "GUILTY_LIGHT",
      "imageId": "9f2c4b1e-7a3d-4e5f-8b6a-0c1d2e3f4a5b",
      "imageUrl": "https://cdn.example.com/memes/guilty-light-01.png"
    }
  },
  "pollAfterMs": 0
}
```

| 필드 | 내용 |
|---|---|
| `schemaVersion` | 늘 `1` |
| `juryStatus` | 배심원 평결 `guilty` \| `notGuilty` \| `agree` \| `disagree` \| `dismissed`(정족수 미달 각하). 아직 투표 중이면 `null` |
| `sentenceStatus` | `PENDING`(형량 미확정) \| `FINAL` |
| `textStatus` | `PENDING` \| `GENERATING` \| `TEMPLATE_READY`(템플릿 문구) \| `AI_READY`(AI 문구) |
| `textVersion` | 문구 버전. 문구가 바뀔 때마다 커진다. 투표 중이면 `0` |
| `view` | 보여 줄 문구. 투표 중·생성 중·각하면 `null` |
| `view.statement` | 판결문 문장 배열 |
| `view.sentence`·`view.sentenceLabel` | 형량 코드 `probation` \| `oneDay` \| `life` 와 표시 문구. 무죄·동의·기각이면 `null` |
| `view.sentencingReason` | 양형 이유. `source` 가 `TEMPLATE` 이면 `null` |
| `view.source` | `AI` \| `TEMPLATE`. `TEMPLATE` 이면 AI 판사 라벨과 양형 이유 블록을 숨긴다 |
| `view.meme` | 짤 이미지 metadata. 없으면 `null` |
| `pollAfterMs` | 다음 조회까지 서버가 권장하는 대기(ms). 아래 표 |

| 상태 | `view` | `pollAfterMs` |
|---|---|---|
| 투표 중(`juryStatus: null`) | `null` | `5000` |
| `textStatus` `PENDING`·`GENERATING` | `null` | `1000` |
| `TEMPLATE_READY` | 템플릿 문구 | `30000` |
| `AI_READY` | AI 문구 | `0` |
| `juryStatus: dismissed` | `null` | `0` |

- 게시물 삭제·공유 철회 뒤에 저장된 AI 문구를 쓸 수 없게 되면 곧바로 템플릿 문구(`source: TEMPLATE`)로 바뀐다
- 게시물이 삭제되면 `404`

**폴링 규칙(프론트)**

- 1초 간격으로 시작해 15초가 지나면 5초 간격으로 늦춘다. `pollAfterMs` 는 서버 권장값이다
- 화면을 떠나면 진행 중인 요청과 타이머를 취소한다
- `textStatus` 가 `AI_READY` 이거나 `juryStatus` 가 `dismissed` 면 즉시 멈춘다
- `TEMPLATE_READY` 면 템플릿 문구를 보여 준 뒤 30초 뒤에 한 번 더 보거나, 화면에 다시 들어올 때 본다(AI 문구로 바뀔 수 있다)
- 이미 받은 것보다 `textVersion` 이 작은 응답이 늦게 도착하면 버린다. 그 응답으로 화면을 덮지 않는다

**오류**

| 상황 | 상태 코드 | 바디 |
|---|---|---|
| 게시물 없음·삭제됨·볼 수 없는 사람·`room_id` 가 볼 수 없는 방 | 404 | `{ "message": "게시물을 찾을 수 없습니다." }` |
| `room_id` 가 UUID 가 아님 | 400 | Spring 기본 에러 형식 |
| JWT 없음 | 401 | - |

### `GET /api/posts/{postId}/share-card?room_id={roomId}`

S-11 판결 공유 카드. 방 밖으로 나갈 수 있는 문구와 이미지 metadata 만 준다.
금액·무엇을·사유·투표 사유·배심원·근거 원문·양형 이유는 없다.

- `room_id` 로 어느 방 판결의 카드인지 정한다. 강도는 그 방 강도다
- AI 문장이 공개 근거만 인용했으면 그대로, 방 안에서만 볼 수 있는 근거를 인용했으면 그 문장만 템플릿 문장으로 바뀐다.
  한 문장이라도 바뀌면 `headline` 도 템플릿(`유죄`·`무죄`·`동의`·`기각`)이다
- 삭제·공유 철회로 저장된 AI 문구를 쓸 수 없게 되면 전부 템플릿이다

**Response `200`**
```json
{
  "postId": "3c9a1e7b-5d2f-4b8a-9e6c-1a2b3c4d5e6f",
  "postType": "spent",
  "juryStatus": "guilty",
  "intensity": "mild",
  "headline": "택시비 유죄",
  "statement": ["배심원 3인 중 3인이 유죄로 판단했습니다.", "지하철이 있었습니다."],
  "sentence": "oneDay",
  "sentenceLabel": "징역 1일 (내일 하루 무지출)",
  "meme": {
    "tag": "GUILTY_LIGHT",
    "imageId": "9f2c4b1e-7a3d-4e5f-8b6a-0c1d2e3f4a5b",
    "imageUrl": "https://cdn.example.com/memes/guilty-light-01.png"
  }
}
```

- `sentence`·`sentenceLabel` 은 무죄·동의·기각이면 `null`, `meme` 은 짤이 없으면 `null`

**오류**

| 상황 | 상태 코드 | 바디 |
|---|---|---|
| 게시물 없음·삭제됨·볼 수 없는 사람 | 404 | `{ "message": "게시물을 찾을 수 없습니다." }` |
| 형량이 아직 확정되지 않음(투표 중·생성 중·각하) | 404 | `{ "message": "판결이 아직 확정되지 않았습니다." }` |
| JWT 없음 | 401 | - |

### `DELETE /api/posts/{postId}`

게시물 삭제. 작성자만 할 수 있다. 삭제하면 판결 조회·공유 카드가 곧바로 `404` 가 되고, 진행 중인 AI 작업은 취소된다.

**Response `204`** — 바디 없음. 이미 삭제된 게시물을 작성자가 다시 지워도 `204`

**오류**

| 상황 | 상태 코드 | 바디 |
|---|---|---|
| 게시물 없음 | 404 | `{ "message": "게시물을 찾을 수 없습니다." }` |
| 작성자가 아님 | 403 | `{ "message": "본인 게시물만 삭제할 수 있습니다." }` |
| JWT 없음 | 401 | - |

### GET /api/posts/{postId}/trace

데모 C 관측 화면용 AI trace 조회(10 §4.5). JWT 필요. 백엔드가 AI API 를 프록시한다.

- 게시물 **작성자만** 조회한다. 게시물이 없거나 삭제됐거나 작성자가 아니면 `404 {"message"}`(셋을 구분하지 않는다)
- `200` 본문은 AI trace 를 camelCase 로 바꾼 것이다. 원문·개별 id 는 없다

  | 필드 | 내용 |
  | ---- | ---- |
  | `postId` | 게시물 id |
  | `dossier` | 최신 조서. 없으면 `null`. `labels[]`, `createdAt`, `invalidated`, `evidence[]`(`label`, `epistemicType`, `factType`, `scope{visibility, roomCount}`, `occurredAt`, `invalidated`, `sources[]{sourceType, count}`) |
  | `timeline[]` | 모델 호출 순서. `node`, `callIndex`, `vendor`, `modelId`, `status`, `startedAt`, `finishedAt`, `durationMs`, `promptTokens`, `completionTokens`, `actualMicroUsd` |
  | `cost` | `actualMicroUsd`, `unknownCalls`, `unknownEstimatedMaxMicroUsd`, `calls` |

- trace 기록이 없으면 `404 {"message"}`
- AI 서버 장애(5xx·연결 실패·예상 밖 응답) `502 {"message"}`, AI 서버가 2초 안에 응답하지 않으면 `504 {"message"}`
- JWT 없음 `401`

## 13. 게시물 댓글

12장 게시물(`postId`)에 다는 댓글이다. 4장 격자 칸 댓글(`expenses` 단위)과 테이블·경로가 다르다. 1단계(대댓글 없음), 수정 없음.

- 댓글은 **방 단위**다. 쓸 때 어느 공유 방에서 쓰는지(`roomId`)를 정한다
- 목록을 볼 수 있는 사람은 12장 게시물을 볼 수 있는 사람(작성자·철회되지 않은 공유 방 멤버)이다
- 판결이 확정된 게시물의 댓글은 AI 기억에 쓰인다(판결 확정 전에 단 댓글도 확정 뒤에 쓰인다). 거르는 것은 AI 쪽이 한다
- 요청·응답 JSON 은 camelCase

### `GET /api/posts/{postId}/comments?room_id={roomId}`

댓글 목록. 오래된 순, 삭제된 댓글은 빠진다.

- **`room_id`(권장): 그 방 댓글만 준다. 작성자라도 다른 방 댓글은 보지 않는다.**
  게시물은 여러 방에 올라가지만 댓글 스레드는 방마다 따로다. 화면은 늘 방 안에서 열리므로
  프론트는 항상 이 값을 넘긴다. 요청자가 그 방 멤버가 아니면 `404`
- `room_id` 없이 부르면(옛 동작) 작성자는 공유 방 전체, 그 밖에는 자기가 멤버인 방의 댓글
- 공유가 철회된 방의 댓글은 누구에게도 보이지 않는다

**Response `200`**
```json
[
  {
    "id": "7d2e9a14-3c5b-4f8e-a1d6-0b9c8e7f6a52",
    "postId": "3c9a1e7b-5d2f-4b8a-9e6c-1a2b3c4d5e6f",
    "roomId": "6a1f0c2e-3b7d-4c55-9d7e-2f1b8c0a9e41",
    "userId": "6db45245-5518-40e8-92dc-7e8daf8e65fc",
    "nickname": "짠돌이",
    "content": "택시 세 번째는 선 넘었다",
    "createdAt": "2026-09-15T12:05:40.123456Z"
  }
]
```

| 필드 | 내용 |
|---|---|
| `roomId` | 댓글을 단 방 |
| `userId`·`nickname` | 댓글 작성자와 프로필 닉네임 |

**오류**

| 상황 | 상태 코드 | 바디 |
|---|---|---|
| 게시물 없음·삭제됨·볼 수 없는 사람 | 404 | `{ "message": "게시물을 찾을 수 없습니다." }` |
| JWT 없음 | 401 | - |

### `POST /api/posts/{postId}/comments`

댓글 작성. 게시물 작성자도 자기가 멤버인 공유 방에서는 댓글을 달 수 있다.

**Request**
```json
{
  "roomId": "6a1f0c2e-3b7d-4c55-9d7e-2f1b8c0a9e41",
  "content": "택시 세 번째는 선 넘었다"
}
```

| 필드 | 규칙 |
|---|---|
| `roomId` | 필수. 이 게시물이 공유된(철회되지 않은) 방이고 요청자가 그 방 멤버여야 한다 |
| `content` | 필수. 1~200자, 공백만은 안 된다. 앞뒤 공백을 지우지 않고 그대로 저장한다 |

**Response `201`** — 목록 한 건과 같은 모양

**오류** — 아래 순서로 판정한다

| 상황 | 상태 코드 | 바디 |
|---|---|---|
| 게시물 없음·삭제됨·볼 수 없는 사람 | 404 | `{ "message": "게시물을 찾을 수 없습니다." }` |
| `roomId` 없음·UUID 아님 | 400 | `{ "message": "댓글을 달 방을 지정해 주세요." }` |
| `roomId` 가 공유 방이 아니거나, 공유가 철회됐거나, 요청자가 그 방 멤버가 아님 | 403 | `{ "message": "이 방에서는 댓글을 달 수 없습니다." }` |
| `content` 없음·빈 값·공백만 | 400 | `{ "message": "댓글 내용을 입력해 주세요." }` |
| `content` 200자 초과 | 400 | `{ "message": "댓글은 200자 이하여야 합니다." }` |
| JSON 이 깨짐 | 400 | Spring 기본 에러 형식 |
| JWT 없음 | 401 | - |

### `DELETE /api/posts/{postId}/comments/{commentId}`

본인 댓글 삭제. 방 공유가 철회돼 게시물을 볼 수 없게 됐어도 본인 댓글은 지울 수 있다. 지운 댓글은 AI 기억에서도 곧바로 쓰이지 않는다.

**Response `204`** — 바디 없음. 이미 삭제된 댓글을 본인이 다시 지워도 `204`

**오류** — 아래 순서로 판정한다

| 상황 | 상태 코드 | 바디 |
|---|---|---|
| 게시물 없음·삭제됨 | 404 | `{ "message": "게시물을 찾을 수 없습니다." }` |
| 댓글 없음·이 게시물의 댓글이 아님 | 404 | `{ "message": "댓글을 찾을 수 없습니다." }` |
| 남의 댓글인데 게시물을 볼 수 없는 사람 | 404 | `{ "message": "게시물을 찾을 수 없습니다." }` |
| 남의 댓글(게시물 작성자여도) | 403 | `{ "message": "본인 댓글만 삭제할 수 있습니다." }` |
| `postId`·`commentId` 가 UUID 아님 | 400 | Spring 기본 에러 형식 |
| JWT 없음 | 401 | - |
