import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, courseApi, getAccessToken, setAccessToken } from "@/lib/api";

/**
 * 401 재발급 동작 테스트.
 *
 * Access Token 은 30분이다. 72분 녹음 뒤 종료를 누르면 그 뒤의 모든 요청이 401 로 실패했다.
 * 그 경로를 고정해 둔다.
 */
describe("401 을 만나면 재발급하고 한 번 다시 보낸다", () => {
  const json = (body: unknown, status = 200) =>
    new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json" },
    });

  beforeEach(() => setAccessToken("expired-token"));
  afterEach(() => {
    vi.unstubAllGlobals();
    setAccessToken(null);
  });

  it("만료된 토큰이면 refresh 후 같은 요청을 재시도한다", async () => {
    const calls: string[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string, init?: RequestInit) => {
        const path = new URL(url).pathname;
        calls.push(`${init?.method ?? "GET"} ${path}`);
        if (path === "/api/v1/auth/refresh") {
          return Promise.resolve(json({ accessToken: "fresh-token", expiresIn: 1800 }));
        }
        // 첫 호출은 만료 토큰이라 401, 재발급 뒤에는 성공
        const auth = (init?.headers as Record<string, string>)?.Authorization;
        return Promise.resolve(
          auth === "Bearer fresh-token"
            ? json([{ courseId: 17, title: "데이터베이스" }])
            : json({ detail: "만료" }, 401),
        );
      }),
    );

    const courses = await courseApi.list();

    expect(courses).toEqual([{ courseId: 17, title: "데이터베이스" }]);
    expect(calls).toEqual([
      "GET /api/v1/courses",
      "POST /api/v1/auth/refresh",
      "GET /api/v1/courses",
    ]);
    // 재발급된 토큰이 메모리에 반영된다
    expect(getAccessToken()).toBe("fresh-token");
  });

  it("재발급도 실패하면 401 을 올린다 (로그인 화면으로 보내야 한다)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string) =>
        Promise.resolve(
          new URL(url).pathname === "/api/v1/auth/refresh"
            ? json({ detail: "쿠키 없음" }, 401)
            : json({ detail: "만료" }, 401),
        ),
      ),
    );

    await expect(courseApi.list()).rejects.toBeInstanceOf(ApiError);
  });

  it("동시에 여러 요청이 401 을 만나도 재발급은 한 번만 한다", async () => {
    let refreshes = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string, init?: RequestInit) => {
        const path = new URL(url).pathname;
        if (path === "/api/v1/auth/refresh") {
          refreshes += 1;
          return Promise.resolve(json({ accessToken: "fresh-token", expiresIn: 1800 }));
        }
        const auth = (init?.headers as Record<string, string>)?.Authorization;
        return Promise.resolve(
          auth === "Bearer fresh-token" ? json([]) : json({ detail: "만료" }, 401),
        );
      }),
    );

    await Promise.all([courseApi.list(), courseApi.list(), courseApi.list()]);

    expect(refreshes).toBe(1);
  });

  it("인증 API 자체는 재시도하지 않는다 (무한 루프 방지)", async () => {
    const calls: string[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string) => {
        calls.push(new URL(url).pathname);
        return Promise.resolve(json({ detail: "쿠키 없음" }, 401));
      }),
    );

    await expect(courseApi.chat).toBeDefined();
    const { authApi } = await import("@/lib/api");
    await expect(authApi.refresh()).rejects.toBeInstanceOf(ApiError);
    expect(calls).toEqual(["/api/v1/auth/refresh"]);
  });
});
