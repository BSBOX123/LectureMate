import type { LectureResponse } from "@/types/api";
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

  list: () => request<LectureResponse[]>("/api/v1/lectures"),

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
