"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useAuth } from "@/components/AuthProvider";
import { authApi } from "@/lib/api";

/** 회원가입 화면 (SPEC §2.1-6). 가입 후 곧바로 로그인한다. */
export default function SignupPage() {
  const router = useRouter();
  const { login } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await authApi.signup({ email, password, name });
      await login(email, password);
      router.push("/");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "회원가입에 실패했습니다.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className="mx-auto flex w-full max-w-sm flex-1 flex-col justify-center gap-4 p-6">
      <h1 className="text-xl font-semibold">회원가입</h1>
      <form className="flex flex-col gap-3" onSubmit={handleSubmit}>
        <input
          className="rounded border px-3 py-2"
          type="text"
          required
          maxLength={100}
          placeholder="이름"
          value={name}
          onChange={(event) => setName(event.target.value)}
        />
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
          minLength={8}
          placeholder="비밀번호 (8자 이상)"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />
        {error && <p className="text-sm text-red-600">{error}</p>}
        <button
          className="rounded bg-zinc-900 px-3 py-2 text-white disabled:opacity-50"
          type="submit"
          disabled={submitting}
        >
          {submitting ? "가입 중..." : "회원가입"}
        </button>
      </form>
      <p className="text-sm text-zinc-600">
        이미 계정이 있으신가요?{" "}
        <Link className="underline" href="/login">
          로그인
        </Link>
      </p>
    </main>
  );
}
