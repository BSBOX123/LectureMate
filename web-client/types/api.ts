/**
 * Spring Boot <-> Client API 계약 타입 (SPEC §2.1).
 *
 * 과목(course) 하나가 PDF 자료(material) 여러 개와 녹음(recording) 여러 개를 담는다.
 * 질의응답은 과목 단위로, 그 안의 모든 자료와 녹음을 함께 검색한다.
 */

export type MaterialStatus = "PROCESSING" | "READY" | "FAILED";

export type RecordingStatus =
  | "CREATED"
  | "RECORDING"
  | "UPLOADED"
  | "ANALYZING"
  | "READY"
  | "FAILED";

/** §2.1-1 POST /api/v1/courses 응답 및 과목 조회 응답 */
export interface CourseResponse {
  courseId: number;
  title: string;
}

/** §2.1-4 POST /api/v1/courses/{courseId}/materials 응답 및 자료 목록 항목 */
export interface MaterialResponse {
  materialId: number;
  title: string;
  status: MaterialStatus;
  pdfUrl: string | null;
  /** 파싱이 끝나면 설정된다 */
  totalPages: number | null;
}

/** §2.1-7 POST /api/v1/courses/{courseId}/recordings 응답 및 녹음 목록 항목 */
export interface RecordingResponse {
  recordingId: number;
  title: string;
  status: RecordingStatus;
  /** 녹음이 끝나 WAV 가 저장되면 설정된다 */
  audioUrl: string | null;
  /** 전사가 끝나면 설정된다 */
  durationMs: number | null;
}

/** §2.1-11 POST /api/v1/courses/{courseId}/chat 요청 (응답은 SSE) */
export interface ChatRequest {
  question: string;
}

/**
 * §2.1-11 citations 이벤트의 근거 한 건.
 *
 * 자료에서 찾았으면 materialId·materialTitle·pageNumber 가,
 * 녹음에서 찾았으면 recordingId·recordingTitle·startTimeMs 가 채워진다.
 */
export interface Citation {
  source: "MATERIAL" | "RECORDING";
  snippet: string;
  materialId?: number | null;
  materialTitle?: string | null;
  pageNumber?: number | null;
  recordingId?: number | null;
  recordingTitle?: string | null;
  startTimeMs?: number | null;
}
