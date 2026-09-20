package com.lecturemate.api.dto;

/** SPEC §2.1-12 슬라이드 타임라인 한 항목. */
public record SlideTimelineItem(int pageNumber, long speechDurationMs, boolean hasExamHint) {}
