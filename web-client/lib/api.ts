import { parseSseBuffer } from "@/lib/sse";
import type {
  Citation,
  LectureResponse,
  PageAnnotationResponse,
  RecordingFinishResponse,
  SlideTimelineResponse,
} from "@/types/api";
import type {
  LoginRequest,
  SignupRequest,
  TokenResponse,
  UserResponse,
} from "@/types/auth";

const BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

/**
 * Access Token은 메모리에만 둔다. localStorage에 두면 XSS로 탈취될 수 있고,
 * 재발급은 httpOnly 쿠키의 Refresh Token으로 처리한다 (SPEC §2.1-8).
 */
let accessToken: string | null = null;

export function getAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${BASE_URL}${path}`, {
    ...init,
    // Refresh Token 쿠키를 주고받기 위해 필요
    credentials: "include",
    headers: {
      ...(init.body && !(init.body instanceof FormData)
        ? { "Content-Type": "application/json" }
        : {}),
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      ...init.headers,
    },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await errorMessage(response));
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/** 서버는 RFC 9457 ProblemDetail 로 오류를 준다. */
async function errorMessage(response: Response): Promise<string> {
  try {
    const problem = (await response.json()) as { detail?: string };
    return problem.detail ?? `요청이 실패했습니다 (${response.status})`;
  } catch {
    return `요청이 실패했습니다 (${response.status})`;
  }
}

export const authApi = {
  signup: (body: SignupRequest) =>
    request<UserResponse>("/api/v1/auth/signup", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  login: async (body: LoginRequest) => {
    const token = await request<TokenResponse>("/api/v1/auth/login", {
      method: "POST",
      body: JSON.stringify(body),
    });
    setAccessToken(token.accessToken);
    return token;
  },

  /** 새로고침 후 세션 복구용. 쿠키가 없거나 만료면 401 */
  refresh: async () => {
    const token = await request<TokenResponse>("/api/v1/auth/refresh", {
      method: "POST",
    });
    setAccessToken(token.accessToken);
    return token;
  },

  logout: async () => {
    try {
      await request<void>("/api/v1/auth/logout", { method: "POST" });
    } finally {
      setAccessToken(null);
    }
  },

  me: () => request<UserResponse>("/api/v1/users/me"),
};

export const lectureApi = {
  /** §2.1-1 Multipart 업로드. Content-Type 은 브라우저가 boundary 와 함께 붙인다. */
  create: (title: string, file: File) => {
    const form = new FormData();
    form.append("title", title);
    form.append("file", file);
    return request<LectureResponse>("/api/v1/lectures", { method: "POST", body: form });
  },

  get: (lectureId: number) => request<LectureResponse>(`/api/v1/lectures/${lectureId}`),

  /** §2.1-3 녹음 종료 후 정밀 분석 시작 */
  finishRecording: (lectureId: number) =>
    request<RecordingFinishResponse>(`/api/v1/lectures/${lectureId}/recording/finish`, {
      method: "POST",
    }),

  list: () => request<LectureResponse[]>("/api/v1/lectures"),

  /**
   * §2.1-5 RAG 질의응답. SSE 라 fetch 스트림을 직접 읽는다.
   * citations → token... → done 순서로 콜백을 호출한다.
   */
  chat: async (
    lectureId: number,
    question: string,
    handlers: {
      onCitations: (citations: Citation[]) => void;
      onToken: (text: string) => void;
      onDone: (finishReason: string) => void;
    },
    signal?: AbortSignal,
  ) => {
    const response = await fetch(`${BASE_URL}/api/v1/lectures/${lectureId}/chat`, {
      method: "POST",
      credentials: "include",
      signal,
      headers: {
        "Content-Type": "application/json",
        ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      },
      body: JSON.stringify({ question }),
    });
    if (!response.ok || !response.body) {
      throw new ApiError(response.status, "답변을 받지 못했습니다.");
    }

    const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
    let buffer = "";
    for (;;) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      buffer += value;
      const { events, rest } = parseSseBuffer(buffer);
      buffer = rest;
      for (const event of events) {
        const payload = JSON.parse(event.data) as Record<string, unknown>;
        if (event.event === "citations") {
          handlers.onCitations(payload.citations as Citation[]);
        } else if (event.event === "token") {
          handlers.onToken(payload.text as string);
        } else if (event.event === "done") {
          handlers.onDone(payload.finishReason as string);
        }
      }
    }
  },

  /** §2.1-12 슬라이드별 발화 분량과 시험 힌트 유무 */
  timeline: (lectureId: number) =>
    request<SlideTimelineResponse[]>(`/api/v1/lectures/${lectureId}/timeline`),

  /** §2.1-13 강의 삭제 (파일 포함) */
  remove: (lectureId: number) =>
    request<void>(`/api/v1/lectures/${lectureId}`, { method: "DELETE" }),

  /** §2.1-14 실패한 분석 재시도 */
  retry: (lectureId: number) =>
    request<LectureResponse>(`/api/v1/lectures/${lectureId}/retry`, { method: "POST" }),

  /** §2.1-4 슬라이드 자동 필기. 분석 전이면 404 */
  annotations: (lectureId: number, pageNumber: number) =>
    request<PageAnnotationResponse>(
      `/api/v1/lectures/${lectureId}/pages/${pageNumber}/annotations`,
    ),

  /** PDF 는 인증이 필요하므로 fetch 로 받아 blob URL 로 변환한다. */
  pdfObjectUrl: async (pdfUrl: string) => {
    const response = await fetch(`${BASE_URL}${pdfUrl}`, {
      credentials: "include",
      headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : {},
    });
    if (!response.ok) {
      throw new ApiError(response.status, "PDF를 불러오지 못했습니다.");
    }
    return URL.createObjectURL(await response.blob());
  },
};
