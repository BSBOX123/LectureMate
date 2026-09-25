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

/** 과목에 속한 PDF 자료 ({@code course_materials}). 한 과목에 여러 개 올릴 수 있다. */
@Entity
@Table(name = "course_materials")
public class CourseMaterial {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "course_id", nullable = false)
  private Course course;

  @Column(nullable = false)
  private String title;

  @Column(name = "pdf_url", length = 500)
  private String pdfUrl;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 50)
  private MaterialStatus status = MaterialStatus.PROCESSING;

  @Column(name = "total_pages")
  private Integer totalPages;

  @CreationTimestamp
  @Column(name = "created_at", updatable = false)
  private OffsetDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  protected CourseMaterial() {}

  public CourseMaterial(Course course, String title) {
    this.course = course;
    this.title = title;
  }

  /** 업로드 완료: 내려받을 URL 을 기록하고 파싱 대기 상태로 둔다. */
  public void attachPdf(String pdfUrl) {
    this.pdfUrl = pdfUrl;
    this.status = MaterialStatus.PROCESSING;
  }

  /** 파싱 완료: 쪽수를 기록하고 검색 가능 상태로 바꾼다. */
  public void markReady(Integer totalPages) {
    this.totalPages = totalPages;
    this.status = MaterialStatus.READY;
  }

  public void markFailed() {
    this.status = MaterialStatus.FAILED;
  }

  public void markProcessing() {
    this.status = MaterialStatus.PROCESSING;
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

  public String getPdfUrl() {
    return pdfUrl;
  }

  public MaterialStatus getStatus() {
    return status;
  }

  public Integer getTotalPages() {
    return totalPages;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
