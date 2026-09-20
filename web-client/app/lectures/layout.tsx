"use client";

import { useRouter, usePathname } from "next/navigation";
import { useEffect, type ReactNode } from "react";
import { useAuth } from "@/components/AuthProvider";

/**
 * /lectures 아래 모든 화면은 로그인이 필요하다.
 *
 * 로그인하지 않았으면 /login 으로 보내고, 로그인 후 원래 가려던 곳으로 돌아오도록
 * 현재 경로를 next 파라미터로 넘긴다.
 */
export default function LecturesLayout({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();
  const router = useRouter();
  const pathname = usePathname();

  useEffect(() => {
    if (!loading && !user) {
      router.replace(`/login?next=${encodeURIComponent(pathname)}`);
    }
  }, [loading, user, router, pathname]);

  if (loading) {
    return <p className="p-6 text-sm text-zinc-500">불러오는 중...</p>;
  }
  if (!user) {
    return <p className="p-6 text-sm text-zinc-500">로그인 화면으로 이동합니다...</p>;
  }
  return <>{children}</>;
}
