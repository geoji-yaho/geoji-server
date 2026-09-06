package com.ttegeoji.backend.repository;

import com.ttegeoji.backend.domain.Comment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CommentRepository extends JpaRepository<Comment, UUID> {
    List<Comment> findByExpenseIdOrderByCreatedAtAsc(UUID expenseId);
}
