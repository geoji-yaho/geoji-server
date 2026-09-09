package com.ttegeoji.backend.repository;

import com.ttegeoji.backend.domain.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface ExpenseRepository extends JpaRepository<Expense, UUID> {

    // 그리드 화면: 이 방 "멤버들"의 최근 지출을 시간 내림차순으로.
    // 지출은 방 소유가 아니라 유저 소유라서 room_members를 거쳐야 한다 — 그래야 한 유저가
    // 여러 방에 속해있을 때 그 지출이 모든 방의 그리드에 같이 뜬다.
    @Query("""
            select e from Expense e
            where e.userId in (select rm.id.userId from RoomMember rm where rm.id.roomId = :roomId)
              and e.spentAt between :from and :to
            order by e.spentAt desc
            """)
    List<Expense> findForRoomGrid(
            @Param("roomId") UUID roomId, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
