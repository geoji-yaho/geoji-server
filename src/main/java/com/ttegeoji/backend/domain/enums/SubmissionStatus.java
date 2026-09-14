package com.ttegeoji.backend.domain.enums;

// submissions.status(10 §2·§9). DB 는 text + CHECK.
public enum SubmissionStatus {
    NEW, NEEDS_INPUT, COMPLETED, BLOCKED, EXPIRED
}
