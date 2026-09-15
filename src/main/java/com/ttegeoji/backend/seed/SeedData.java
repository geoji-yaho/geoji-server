package com.ttegeoji.backend.seed;

import com.ttegeoji.backend.domain.enums.PostType;
import com.ttegeoji.backend.domain.enums.SpiceLevel;

import java.util.List;

/**
 * 10 §12 데모 시드 문구·금액. 방 3·사용자 4·게시물 12(카테고리 6 × 2)·댓글 20.
 * 판결 문구는 여기 두지 않는다 — 평결을 확정하면 SENTENCE 파이프라인이 만든다.
 * 사용자 번호: 0 = 데모 C, 1~3 = 배심원 역. 방 번호: 0 순한맛, 1 매운맛(데모 C 방), 2 지옥맛.
 */
final class SeedData {

    private SeedData() {
    }

    static final int USER_COUNT = 4;
    static final int DEMO_USER = 0;
    static final int DEMO_ROOM = 1;

    // ProfileController 온보딩 기본값과 같다
    static final int MONTHLY_BUDGET = 500_000;
    // 10 §12 마감 30분
    static final int VOTE_DEADLINE_MINUTES = 30;

    static final List<String> NICKNAMES = List.of("커피수혈러", "택시탈출기", "배달끊기1일차", "짠내장인");

    record RoomSeed(String name, SpiceLevel spiceLevel, List<String> rules, List<Integer> members) {
    }

    // 규칙은 geoji-web RoomRuleEditor RULE_PRESETS(택시·배달·커피 포함) 에서 3~5개
    static final List<RoomSeed> ROOMS = List.of(
            new RoomSeed("순한맛 거지방", SpiceLevel.mild,
                    List.of("한 달에 배달음식 1번", "한 달에 택시 1번", "커피는 하루 1잔"),
                    List.of(0, 1, 2)),
            new RoomSeed("매운맛 거지방", SpiceLevel.spicy,
                    List.of("한 달에 배달음식 1번", "한 달에 택시 1번", "커피는 하루 1잔", "충동구매 금지"),
                    List.of(0, 1, 2, 3)),
            new RoomSeed("지옥맛 거지방", SpiceLevel.hell,
                    List.of("한 달에 배달음식 1번", "한 달에 택시 1번", "커피는 하루 1잔", "충동구매 금지", "주말 외식 금지"),
                    List.of(0, 2, 3)));

    record VoteSeed(int voter, String verdict, String reason) {
    }

    record CommentSeed(int user, String content) {
    }

    /**
     * expected 는 표로 나와야 할 평결(null = 투표 중). 투표 중 글은 투표 가능 인원보다 표가 적어 마감 전까지 확정되지 않는다.
     */
    record PostSeed(String label, int author, int room, PostType postType, int amountKrw, String category,
                    String item, String reason, List<VoteSeed> votes, String expected, List<CommentSeed> comments) {
    }

    // AI 저장소 scripts/seed_memory_demo_c.py DEMO_ITEM·DEMO_CATEGORY·DEMO_AMOUNT_KRW, reason 없음
    static final String STARBUCKS_ITEM = "스타벅스";
    static final String STARBUCKS_CATEGORY = "카페/간식";
    static final int STARBUCKS_AMOUNT_KRW = 6_100;

    static final List<PostSeed> POSTS = List.of(
            // 카페/간식 × 2 — 데모 C 스타벅스. 배심원 3명 중 2유죄(0.67)·3유죄(1.0)(코디네이터 9/15)
            new PostSeed("스타벅스-1", 0, 1, PostType.spent, STARBUCKS_AMOUNT_KRW, STARBUCKS_CATEGORY,
                    STARBUCKS_ITEM, null,
                    List.of(new VoteSeed(1, "guilty", "커피는 하루 1잔 규칙 있잖아요"),
                            new VoteSeed(2, "guilty", "사무실 믹스커피가 공짜입니다"),
                            new VoteSeed(3, "notGuilty", "이건 인정. 6천원이면 봐줄 만함")),
                    "guilty",
                    List.of(new CommentSeed(1, "스타벅스 가는 길에 편의점 커피 1,500원짜리 지나쳤죠?"),
                            new CommentSeed(3, "저는 무죄 줬지만 다음 달에도 이러면 저도 유죄 갑니다 ㅋㅋ"))),
            new PostSeed("스타벅스-2", 0, 1, PostType.spent, STARBUCKS_AMOUNT_KRW, STARBUCKS_CATEGORY,
                    STARBUCKS_ITEM, null,
                    List.of(new VoteSeed(1, "guilty", "또 스타벅스요? 이번 주만 두 번째"),
                            new VoteSeed(2, "guilty", "텀블러 할인이라도 받으셨어야죠"),
                            new VoteSeed(3, "guilty", "커피는 하루 1잔인데 이건 습관입니다")),
                    "guilty",
                    List.of(new CommentSeed(2, "같은 가게 같은 금액 두 번이면 이제 월 고정비로 잡으셔야겠네요"),
                            new CommentSeed(3, "만장일치 유죄 축하드립니다. 내일은 집에서 커피 타 오시죠 ㅎㅎ"))),
            // 배달 × 2
            new PostSeed("치킨 배달", 1, 0, PostType.spent, 24_000, "배달", "치킨 배달",
                    "야근하고 집에 왔는데 밥이 없었어요",
                    List.of(new VoteSeed(0, "guilty", "한 달에 배달음식 1번 규칙 벌써 쓰셨어요"),
                            new VoteSeed(2, "guilty", "라면은 900원이에요")),
                    "guilty",
                    List.of(new CommentSeed(0, "야근은 고생하셨지만 배달비까지 내신 건 조금 아쉬워요"),
                            new CommentSeed(2, "다음엔 퇴근길에 도시락 하나 챙겨 가시는 건 어떨까요?"))),
            new PostSeed("마라탕 배달", 2, 2, PostType.spent, 18_500, "배달", "마라탕 배달",
                    "비가 너무 와서 나가기 싫었음",
                    List.of(new VoteSeed(0, "notGuilty", "폭우면 정상 참작 가능"),
                            new VoteSeed(3, "notGuilty", "이번 달 첫 배달이라 봐준다")),
                    "notGuilty",
                    List.of(new CommentSeed(0, "비 핑계 이번 한 번만 통한다. 다음 비 오는 날엔 라면 끓여"),
                            new CommentSeed(3, "무죄 받았다고 좋아하지 마라. 배달 규칙 한 번 남은 거 기억해"))),
            // 교통/택시 × 2
            new PostSeed("택시", 3, 1, PostType.spent, 14_200, "교통/택시", "택시",
                    "늦잠 자서 지각할 뻔했어요",
                    List.of(new VoteSeed(0, "guilty", "지하철이 있었잖아요"),
                            new VoteSeed(1, "guilty", "알람을 다섯 개 맞추세요"),
                            new VoteSeed(2, "guilty", "늦잠은 정상 참작 사유가 아닙니다")),
                    "guilty",
                    List.of(new CommentSeed(1, "늦잠 값 14,200원이면 알람시계를 사는 게 더 싸겠는데요"),
                            new CommentSeed(2, "지각 면한 대가로 이번 달 택시 규칙 끝났습니다. 수고하셨어요"))),
            new PostSeed("심야 택시", 1, 0, PostType.spent, 32_000, "교통/택시", "심야 택시",
                    "막차가 끊겨서 어쩔 수 없었어요",
                    List.of(new VoteSeed(0, "notGuilty", "막차 끊긴 건 어쩔 수 없죠"),
                            new VoteSeed(2, "notGuilty", "안전하게 귀가한 게 먼저예요")),
                    "notGuilty",
                    List.of(new CommentSeed(0, "막차 끊기면 정말 방법이 없죠. 무사히 들어가셔서 다행이에요"),
                            new CommentSeed(2, "다음엔 막차 시간 알람 맞춰 두시면 3만원 아끼실 수 있어요"))),
            // 술/유흥 × 2
            new PostSeed("2차 술값", 2, 1, PostType.spent, 45_000, "술/유흥", "2차 술값",
                    "동기 생일이라 빠질 수 없었음",
                    List.of(new VoteSeed(0, "guilty", "1차에서 축하는 충분했어요"),
                            new VoteSeed(1, "guilty", "2차는 선택이었습니다"),
                            new VoteSeed(3, "notGuilty", "생일은 1년에 한 번이니까")),
                    "guilty",
                    List.of(new CommentSeed(0, "생일 축하는 1차까지만 인정이죠. 2차는 본인 의지였잖아요 ㅋㅋ"),
                            new CommentSeed(3, "저는 무죄 줬는데 45,000원 보고 살짝 후회했습니다 진짜로"))),
            new PostSeed("편의점 맥주", 3, 2, PostType.spent, 11_000, "술/유흥", "편의점 맥주 4캔",
                    "퇴근길 스트레스 해소",
                    List.of(new VoteSeed(0, "guilty", "4캔은 스트레스 핑계로 너무 많다"),
                            new VoteSeed(2, "guilty", "물 마셔")),
                    "guilty",
                    List.of(new CommentSeed(0, "스트레스를 4캔으로 푸는 건 해소가 아니라 적립이다 정신 차려"),
                            new CommentSeed(2, "만 원 넘게 마시고 해소라고? 내일 아침에 통장 보고 다시 말해봐"))),
            // 쇼핑/패션 × 2 — 고민 중(동의·기각)
            new PostSeed("무선 이어폰", 1, 0, PostType.considering, 189_000, "쇼핑/패션", "무선 이어폰",
                    "기존 이어폰 한쪽이 안 들려요",
                    List.of(new VoteSeed(0, "agree", "고장 났으면 사야죠"),
                            new VoteSeed(2, "agree", "매일 쓰는 물건이면 인정")),
                    "agree",
                    List.of(new CommentSeed(0, "고장 난 거면 필요한 소비 맞아요. 대신 최저가 꼭 비교하세요"),
                            new CommentSeed(2, "매일 출퇴근에 쓰시는 거라 동의했어요. 오래 쓰시길 바랄게요"))),
            new PostSeed("한정판 운동화", 2, 2, PostType.considering, 239_000, "쇼핑/패션", "한정판 운동화",
                    "리셀하면 오히려 이득일지도",
                    List.of(new VoteSeed(0, "disagree", "리셀은 꿈이다"),
                            new VoteSeed(3, "disagree", "신발장에 운동화 몇 켤레야")),
                    "disagree",
                    List.of(new CommentSeed(0, "리셀로 돈 번 사람보다 신발장에 쌓아 둔 사람이 백 배는 많다"),
                            new CommentSeed(3, "이득일지도라는 말 나오면 이미 진 거다. 지갑 닫고 잠이나 자라"))),
            // 구독 × 2 — 투표 중(표가 투표 가능 인원보다 적다)
            new PostSeed("OTT 추가 구독", 0, 0, PostType.spent, 13_500, "구독", "OTT 추가 구독",
                    "보고 싶은 드라마가 거기만 있어요",
                    List.of(new VoteSeed(1, "guilty", "한 달 보고 해지하실 거죠?")),
                    null, List.of()),
            new PostSeed("음악 스트리밍", 3, 1, PostType.spent, 10_900, "구독", "음악 스트리밍",
                    "출퇴근길에 음악이 없으면 못 버팀",
                    List.of(new VoteSeed(0, "notGuilty", "매일 쓰면 인정")),
                    null, List.of()));
}
