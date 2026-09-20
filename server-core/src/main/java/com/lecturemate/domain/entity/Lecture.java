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

/** 강의 메타데이터 ({@code lectures}). Spring Boot 가 생성/수정한다. */
@Entity
@Table(name = "lectures")
public class Lecture {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(nullable = false)
  private String title;

  @Column(name = "pdf_url", length = 500)
  private String pdfUrl;

  @Column(name = "audio_url", length = 500)
  private String audioUrl;

  @Enumerated(EnumType.STRING)
  @Column(length = 50)
  private LectureStatus status = LectureStatus.INITIALIZED;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  private OffsetDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  protected Lecture() {}

  public Lecture(User user, String title) {
    this.user = user;
    this.title = title;
  }

  /** PDF 업로드 완료: 클라이언트가 내려받을 URL 을 기록하고 파싱 대기 상태로 만든다. */
  public void attachPdf(String pdfUrl) {
    this.pdfUrl = pdfUrl;
    this.status = LectureStatus.PROCESSING;
  }

  public void changeStatus(LectureStatus status) {
    this.status = status;
  }

  public Long getId() {
    return id;
  }

  public User getUser() {
    return user;
  }

  public String getTitle() {
    return title;
  }

  public String getPdfUrl() {
    return pdfUrl;
  }

  public String getAudioUrl() {
    return audioUrl;
  }

  public LectureStatus getStatus() {
    return status;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
