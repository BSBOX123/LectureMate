/**
 * Spring Boot <-> Client API 계약 타입 (SPEC §2.1).
 */

export type LectureStatus =
  | "INITIALIZED"
  | "PROCESSING"
  | "RECORDING"
  | "ANALYZING"
  | "READY"
  | "FAILED";

/** §2.1-1 POST /api/v1/lectures 응답 및 강의 메타데이터 조회 응답 */
export interface LectureResponse {
  lectureId: number;
  title: string;
  status: LectureStatus;
  pdfUrl: string | null;
}

/** §2.1-2 WS /ws/v1/lectures/{lectureId}/audio 서버 → 클라이언트 이벤트 */
export interface TranscriptPreviewEvent {
  type: "TRANSCRIPT_PREVIEW";
  startTimeMs: number;
  endTimeMs: number;
  text: string;
}

/** §2.1-3 POST /api/v1/lectures/{lectureId}/recording/finish 응답 */
export interface RecordingFinishResponse {
  lectureId: number;
  status: LectureStatus;
  message: string;
}

/** PDF 좌표계 [x1, y1, x2, y2] */
export type BBox = [number, number, number, number];

export interface Highlight {
  word: string;
  bbox: BBox;
  color: string;
}

/** §2.1-4 GET /api/v1/lectures/{lectureId}/pages/{pageNumber}/annotations 응답 */
export interface PageAnnotationResponse {
  pageNumber: number;
  professorSummary: string;
  examHints: string | null;
  confidenceScore: number;
  highlights: Highlight[];
}

/** §2.1-5 POST /api/v1/lectures/{lectureId}/chat 요청 (응답은 SSE) */
export interface ChatRequest {
  question: string;
}
