package com.lecturemate.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 과목에 속한 녹음 ({@code course_recordings}). 한 과목에 여러 번 녹음할 수 있다.
 *
 * <p>특정 PDF 자료에 묶이지 않는다. 전사 결과는 과목 단위 검색의 한쪽 축이 된다.
 */
@Entity
@Table(name = "course_recordings")
public class CourseRecording {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "course_id", nullable = false)
  private Course course;

  @Column(nullable = false)
  private String title;

  @Column(name = "audio_url", length = 500)
  private String audioUrl;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 50)
  private RecordingStatus status = RecordingStatus.CREATED;

  @Column(name = "duration_ms")
  private Integer durationMs;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  private OffsetDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  protected CourseRecording() {}

  public CourseRecording(Course course, String title) {
    this.course = course;
    this.title = title;
  }

  public void changeStatus(RecordingStatus status) {
    this.status = status;
  }

  /** 녹음 종료: 저장된 WAV 의 URL 을 기록하고 전사 대기 상태로 둔다. */
  public void attachAudio(String audioUrl) {
    this.audioUrl = audioUrl;
    this.status = RecordingStatus.UPLOADED;
  }

  /** 전사 완료: 녹음 길이를 기록하고 검색 가능 상태로 바꾼다. */
  public void markReady(Integer durationMs) {
    this.durationMs = durationMs;
    this.status = RecordingStatus.READY;
  }

  public Long getId() {
    return id;
  }

  public Course getCourse() {
    return course;
  }

  public String getTitle() {
    return title;
  }

  public String getAudioUrl() {
    return audioUrl;
  }

  public RecordingStatus getStatus() {
    return status;
  }

  public Integer getDurationMs() {
    return durationMs;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
