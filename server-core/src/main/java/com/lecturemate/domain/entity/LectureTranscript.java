package com.lecturemate.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * 오디오 전사 세그먼트 ({@code lecture_transcripts}).
 *
 * <p>FastAPI 배치 분석이 생성하며 Spring Boot 는 조회만 한다. {@code embedding} 컬럼은 매핑하지 않는다.
 */
@Entity
@Table(name = "lecture_transcripts")
public class LectureTranscript {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "lecture_id")
  private Lecture lecture;

  @Column(name = "start_time_ms", nullable = false)
  private int startTimeMs;

  @Column(name = "end_time_ms", nullable = false)
  private int endTimeMs;

  @Column(name = "speaker_text", nullable = false, columnDefinition = "text")
  private String speakerText;

  /** 단조 정렬(Monotonic DP)로 매핑된 슬라이드 번호. 매핑 전이면 null. */
  @Column(name = "matched_slide_page")
  private Integer matchedSlidePage;

  @Column(name = "created_at", insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  protected LectureTranscript() {}

  public Long getId() {
    return id;
  }

  public Lecture getLecture() {
    return lecture;
  }

  public int getStartTimeMs() {
    return startTimeMs;
  }

  public int getEndTimeMs() {
    return endTimeMs;
  }

  public String getSpeakerText() {
    return speakerText;
  }

  public Integer getMatchedSlidePage() {
    return matchedSlidePage;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
