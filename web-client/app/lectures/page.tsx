"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { lectureApi } from "@/lib/api";
import type { LectureResponse } from "@/types/api";

/** 강의 목록. SPEC §4.3에는 없지만 대시보드로 이동하려면 필요해 추가했다. */
export default function LectureListPage() {
  const [lectures, setLectures] = useState<LectureResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<number | null>(null);

  const remove = (lecture: LectureResponse) => {
    // 되돌릴 수 없으므로 한 번 확인한다
    if (!window.confirm(`"${lecture.title}" 강의를 삭제할까요?\n녹음과 분석 결과도 함께 지워집니다.`)) {
      return;
    }
    setDeleting(lecture.lectureId);
    setError(null);
    void lectureApi
      .remove(lecture.lectureId)
      .then(() =>
        setLectures((previous) =>
          (previous ?? []).filter((item) => item.lectureId !== lecture.lectureId),
        ),
      )
      .catch((cause: unknown) =>
        setError(cause instanceof Error ? cause.message : "삭제하지 못했습니다."),
      )
      .finally(() => setDeleting(null));
  };

  useEffect(() => {
    lectureApi
      .list()
      .then(setLectures)
      .catch((cause: unknown) =>
        setError(cause instanceof Error ? cause.message : "목록을 불러오지 못했습니다."),
      );
  }, []);

  return (
    <main className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-4 p-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">내 강의</h1>
        <Link className="rounded bg-zinc-900 px-3 py-1 text-sm text-white" href="/lectures/new">
          새 강의
        </Link>
      </div>
      {error && <p className="text-sm text-red-600">{error}</p>}
      {lectures === null && !error && <p className="text-sm text-zinc-500">불러오는 중...</p>}
      {lectures?.length === 0 && <p className="text-sm text-zinc-500">아직 강의가 없습니다.</p>}
      <ul className="flex flex-col gap-2">
        {lectures?.map((lecture) => (
          <li key={lecture.lectureId} className="flex items-center gap-2">
            <Link
              className="flex flex-1 items-center justify-between rounded border px-3 py-2 hover:bg-zinc-50"
              href={`/lectures/${lecture.lectureId}`}
            >
              <span>{lecture.title}</span>
              <span className="text-xs text-zinc-500">{lecture.status}</span>
            </Link>
            <button
              type="button"
              className="rounded border px-2 py-2 text-xs text-red-600 disabled:opacity-50"
              disabled={deleting === lecture.lectureId}
              onClick={() => remove(lecture)}
            >
              {deleting === lecture.lectureId ? "삭제 중" : "삭제"}
            </button>
          </li>
        ))}
      </ul>
    </main>
  );
}
