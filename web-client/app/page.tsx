"use client";

import Link from "next/link";
import { useAuth } from "@/components/AuthProvider";

export default function Home() {
  const { user, loading, logout } = useAuth();

  return (
    <main className="flex flex-1 flex-col items-center justify-center gap-4">
      <h1 className="text-2xl font-semibold">LectureMate AI</h1>
      {loading ? (
        <p className="text-sm text-zinc-500">불러오는 중...</p>
      ) : user ? (
        <div className="flex flex-col items-center gap-2">
          <p className="text-sm">
            {user.name}님 ({user.email})
          </p>
          <div className="flex gap-3 text-sm">
            <Link className="rounded bg-zinc-900 px-3 py-1 text-white" href="/lectures">
              내 강의
            </Link>
            <button className="rounded border px-3 py-1" onClick={() => void logout()}>
              로그아웃
            </button>
          </div>
        </div>
      ) : (
        <div className="flex gap-3 text-sm">
          <Link className="rounded bg-zinc-900 px-3 py-1 text-white" href="/login">
            로그인
          </Link>
          <Link className="rounded border px-3 py-1" href="/signup">
            회원가입
          </Link>
        </div>
      )}
    </main>
  );
}
