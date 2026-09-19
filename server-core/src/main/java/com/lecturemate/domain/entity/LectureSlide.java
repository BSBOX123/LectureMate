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
 * PDF 슬라이드 페이지 ({@code lecture_slides}).
 *
 * <p>FastAPI 가 PDF 파싱 시 생성하며 Spring Boot 는 조회만 한다. {@code embedding} 컬럼은 FastAPI 의 RAG
 * 검색 전용이므로 매핑하지 않는다.
 */
@Entity
@Table(name = "lecture_slides")
public class LectureSlide {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "lecture_id")
  private Lecture lecture;

  @Column(name = "page_number", nullable = false)
  private int pageNumber;

  @Column(name = "slide_text", nullable = false, columnDefinition = "text")
  private String slideText;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "layout_data", nullable = false)
  private List<LayoutWord> layoutData;

  @Column(name = "created_at", insertable = false, updatable = false)
  private OffsetDateTime createdAt;

  protected LectureSlide() {}

  public Long getId() {
    return id;
  }

  public Lecture getLecture() {
    return lecture;
  }

  public int getPageNumber() {
    return pageNumber;
  }

  public String getSlideText() {
    return slideText;
  }

  public List<LayoutWord> getLayoutData() {
    return layoutData;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
