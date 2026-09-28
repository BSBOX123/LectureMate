import { parseSseBuffer } from "@/lib/sse";
import type {
  ChatTurn,
  Citation,
  CourseResponse,
  MaterialResponse,
  RecordingResponse,
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

function authedFetch(path: string, init: RequestInit = {}): Promise<Response> {
  return fetch(`${BASE_URL}${path}`, {
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
}

/**
 * Access Token 은 30분이면 만료된다. 한 시간 넘는 수업을 녹음하고 종료를 누르면 그 뒤의 모든
 * 요청이 401 로 실패했다 (실제로 72분 녹음에서 겪음). 401 을 만나면 Refresh Token 쿠키로
 * 한 번 재발급하고 같은 요청을 다시 보낸다.
 *
 * 동시에 여러 요청이 401 을 만나도 재발급은 한 번만 하도록 진행 중인 약속을 공유한다.
 */
let refreshing: Promise<void> | null = null;

async function refreshOnce(): Promise<void> {
  refreshing ??= authApi
    .refresh()
    .then(() => undefined)
    .finally(() => {
      refreshing = null;
    });
  return refreshing;
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response = await authedFetch(path, init);

  // 인증 API 자체는 재시도하지 않는다 (무한 루프가 된다)
  if (response.status === 401 && !path.startsWith("/api/v1/auth/")) {
    try {
      await refreshOnce();
      response = await authedFetch(path, init);
    } catch {
      // 재발급도 실패하면 원래의 401 을 그대로 올린다 (로그인 화면으로 보내야 한다)
    }
  }

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

export const courseApi = {
  /** §2.1-1 과목(폴더) 만들기 */
  create: (title: string) =>
    request<CourseResponse>("/api/v1/courses", {
      method: "POST",
      body: JSON.stringify({ title }),
    }),

  list: () => request<CourseResponse[]>("/api/v1/courses"),

  get: (courseId: number) => request<CourseResponse>(`/api/v1/courses/${courseId}`),

  rename: (courseId: number, title: string) =>
    request<CourseResponse>(`/api/v1/courses/${courseId}`, {
      method: "PATCH",
      body: JSON.stringify({ title }),
    }),

  /** §2.1-3 과목 삭제. 안의 자료·녹음과 파일까지 함께 지워진다 */
  remove: (courseId: number) =>
    request<void>(`/api/v1/courses/${courseId}`, { method: "DELETE" }),

  /**
   * §2.1-11 과목 단위 RAG 질의응답. SSE 라 fetch 스트림을 직접 읽는다.
   * citations → token... → done 순서로 콜백을 호출한다.
   */
  chat: async (
    courseId: number,
    question: string,
    history: ChatTurn[],
    handlers: {
      onCitations: (citations: Citation[]) => void;
      onToken: (text: string) => void;
      onDone: (finishReason: string) => void;
    },
    signal?: AbortSignal,
  ) => {
    const send = () =>
      authedFetch(`/api/v1/courses/${courseId}/chat`, {
        method: "POST",
        signal,
        body: JSON.stringify({ question, history }),
      });

    let response = await send();
    // 긴 수업 뒤에는 토큰이 만료돼 있다 (request() 와 같은 처리)
    if (response.status === 401) {
      await refreshOnce();
      response = await send();
    }
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
};

export const materialApi = {
  /** §2.1-4 Multipart 업로드. Content-Type 은 브라우저가 boundary 와 함께 붙인다. */
  upload: (courseId: number, title: string, file: File) => {
    const form = new FormData();
    form.append("title", title);
    form.append("file", file);
    return request<MaterialResponse>(`/api/v1/courses/${courseId}/materials`, {
      method: "POST",
      body: form,
    });
  },

  list: (courseId: number) =>
    request<MaterialResponse[]>(`/api/v1/courses/${courseId}/materials`),

  /** §2.1-5 자료 삭제 (PDF 파일 포함) */
  remove: (courseId: number, materialId: number) =>
    request<void>(`/api/v1/courses/${courseId}/materials/${materialId}`, { method: "DELETE" }),

  /** §2.1-6 파싱 실패한 자료 다시 파싱 */
  retry: (courseId: number, materialId: number) =>
    request<MaterialResponse>(`/api/v1/courses/${courseId}/materials/${materialId}/retry`, {
      method: "POST",
    }),
};

export const recordingApi = {
  /** §2.1-7 녹음할 자리를 만든다. 받은 ID 로 WebSocket 을 연다 */
  create: (courseId: number, title: string) =>
    request<RecordingResponse>(`/api/v1/courses/${courseId}/recordings`, {
      method: "POST",
      body: JSON.stringify({ title }),
    }),

  list: (courseId: number) =>
    request<RecordingResponse[]>(`/api/v1/courses/${courseId}/recordings`),

  /** §2.1-9 녹음 삭제 (오디오·전사 포함) */
  remove: (courseId: number, recordingId: number) =>
    request<void>(`/api/v1/courses/${courseId}/recordings/${recordingId}`, { method: "DELETE" }),

  /** §2.1-10 전사 시작 (실패한 녹음의 재시도도 같은 경로). 자동으로 시작되지 않는다 */
  transcribe: (courseId: number, recordingId: number) =>
    request<RecordingResponse>(`/api/v1/courses/${courseId}/recordings/${recordingId}/transcribe`, {
      method: "POST",
    }),
};

/** 인증이 필요한 파일(PDF)을 fetch 로 받아 blob URL 로 바꾼다. */
export async function fileObjectUrl(fileUrl: string): Promise<string> {
  let response = await authedFetch(fileUrl);
  if (response.status === 401) {
    await refreshOnce();
    response = await authedFetch(fileUrl);
  }
  if (!response.ok) {
    throw new ApiError(response.status, "파일을 불러오지 못했습니다.");
  }
  return URL.createObjectURL(await response.blob());
}
