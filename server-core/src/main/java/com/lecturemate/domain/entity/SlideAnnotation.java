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
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 슬라이드별 자동 필기 및 교수님 요약 ({@code slide_annotations}). SPEC §2.1-4 응답의 원천 데이터.
 *
 * <p>FastAPI 배치 분석이 생성하며 Spring Boot 는 조회만 한다.
 */
@Entity
@Table(name = "slide_annotations")
public class SlideAnnotation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "slide_id")
  private LectureSlide slide;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "lecture_id")
  private Lecture lecture;

  /** {@code (lecture_id, page_number)} 인덱스로 슬라이드 조인 없이 조회하기 위한 비정규화 컬럼. */
  @Column(name = "page_number", nullable = false)
  private int pageNumber;

  @Column(name = "professor_summary", nullable = false, columnDefinition = "text")
  private String professorSummary;

  @Column(name = "exam_hints", columnDefinition = "text")
  private String examHints;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "highlight_bboxes", nullable = false)
  private List<HighlightBox> highlightBboxes;

  @Column(name = "confidence_score", nullable = false)
  private double confidenceScore;

  @Column(name = "created_at", insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  protected SlideAnnotation() {}

  public Long getId() {
    return id;
  }

  public LectureSlide getSlide() {
    return slide;
  }

  public Lecture getLecture() {
    return lecture;
  }

  public int getPageNumber() {
    return pageNumber;
  }

  public String getProfessorSummary() {
    return professorSummary;
  }

  public String getExamHints() {
    return examHints;
  }

  public List<HighlightBox> getHighlightBboxes() {
    return highlightBboxes;
  }

  public double getConfidenceScore() {
    return confidenceScore;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
