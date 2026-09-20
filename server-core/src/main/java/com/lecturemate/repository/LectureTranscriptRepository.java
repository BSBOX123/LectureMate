package com.lecturemate.repository;

import com.lecturemate.domain.entity.LectureTranscript;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LectureTranscriptRepository extends JpaRepository<LectureTranscript, Long> {

  /** 슬라이드별 발화 길이 합계 (SPEC §2.1-12). 매칭되지 않은 세그먼트는 제외한다. */
  @Query(
      """
      select t.matchedSlidePage as pageNumber,
             sum(t.endTimeMs - t.startTimeMs) as speechDurationMs
      from LectureTranscript t
      where t.lecture.id = :lectureId and t.matchedSlidePage is not null
      group by t.matchedSlidePage
      order by t.matchedSlidePage
      """)
  List<SpeechDuration> findSpeechDurations(@Param("lectureId") Long lectureId);

  /** 위 쿼리 결과 투영. */
  interface SpeechDuration {
    int getPageNumber();

    long getSpeechDurationMs();
  }
}
