"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useAuth } from "@/components/AuthProvider";

/** 로그인 화면 (SPEC §2.1-7). */
export default function LoginPage() {
  // useSearchParams 를 쓰려면 Suspense 경계가 필요하다
  return (
    <Suspense fallback={<main className="flex-1" />}>
      <LoginForm />
    </Suspense>
  );
}

function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { login } = useAuth();
  // 라우트 가드가 넘겨준 원래 목적지 (없으면 홈)
  const next = searchParams.get("next") ?? "/";
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await login(email, password);
      router.push(next);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "로그인에 실패했습니다.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className="mx-auto flex w-full max-w-sm flex-1 flex-col justify-center gap-4 p-6">
      <h1 className="text-xl font-semibold">로그인</h1>
      <form className="flex flex-col gap-3" onSubmit={handleSubmit}>
        <input
          className="rounded border px-3 py-2"
          type="email"
          required
          placeholder="이메일"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
        />
        <input
          className="rounded border px-3 py-2"
          type="password"
          required
          placeholder="비밀번호"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />
        {error && <p className="text-sm text-red-600">{error}</p>}
        <button
          className="rounded bg-zinc-900 px-3 py-2 text-white disabled:opacity-50"
          type="submit"
          disabled={submitting}
        >
          {submitting ? "로그인 중..." : "로그인"}
        </button>
      </form>
      <p className="text-sm text-zinc-600">
        계정이 없으신가요?{" "}
        <Link className="underline" href="/signup">
          회원가입
        </Link>
      </p>
    </main>
  );
}
