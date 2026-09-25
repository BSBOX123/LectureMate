"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { courseApi } from "@/lib/api";
import type { CourseResponse } from "@/types/api";

/**
 * 과목 목록. 과목이 자료(PDF)와 녹음을 담는 폴더이자 질의응답의 단위다.
 *
 * 과목 만들기는 제목만 받으면 끝이라 별도 화면 없이 여기서 처리한다.
 */
export default function CourseListPage() {
  const [courses, setCourses] = useState<CourseResponse[] | null>(null);
  const [title, setTitle] = useState("");
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    courseApi
      .list()
      .then(setCourses)
      .catch((cause: unknown) =>
        setError(cause instanceof Error ? cause.message : "목록을 불러오지 못했습니다."),
      );
  }, []);

  const create = (event: React.FormEvent) => {
    event.preventDefault();
    const name = title.trim();
    if (!name || creating) {
      return;
    }
    setCreating(true);
    setError(null);
    void courseApi
      .create(name)
      .then((course) => {
        setCourses((previous) => [course, ...(previous ?? [])]);
        setTitle("");
      })
      .catch((cause: unknown) =>
        setError(cause instanceof Error ? cause.message : "과목을 만들지 못했습니다."),
      )
      .finally(() => setCreating(false));
  };

  const remove = (course: CourseResponse) => {
    // 되돌릴 수 없으므로 한 번 확인한다
    if (
      !window.confirm(
        `"${course.title}" 과목을 삭제할까요?\n안에 있는 자료와 녹음도 모두 지워집니다.`,
      )
    ) {
      return;
    }
    setDeleting(course.courseId);
    setError(null);
    void courseApi
      .remove(course.courseId)
      .then(() =>
        setCourses((previous) =>
          (previous ?? []).filter((item) => item.courseId !== course.courseId),
        ),
      )
      .catch((cause: unknown) =>
        setError(cause instanceof Error ? cause.message : "삭제하지 못했습니다."),
      )
      .finally(() => setDeleting(null));
  };

  return (
    <main className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-4 p-6">
      <h1 className="text-xl font-semibold">내 과목</h1>

      <form className="flex gap-2" onSubmit={create}>
        <input
          className="flex-1 rounded border px-3 py-2 text-sm"
          type="text"
          maxLength={255}
          placeholder="과목 이름 (예: 데이터베이스)"
          value={title}
          onChange={(event) => setTitle(event.target.value)}
        />
        <button
          type="submit"
          className="rounded bg-zinc-900 px-3 py-2 text-sm text-white disabled:opacity-50"
          disabled={creating || title.trim().length === 0}
        >
          {creating ? "만드는 중" : "과목 추가"}
        </button>
      </form>

      {error && <p className="text-sm text-red-600">{error}</p>}
      {courses === null && !error && <p className="text-sm text-zinc-500">불러오는 중...</p>}
      {courses?.length === 0 && (
        <p className="text-sm text-zinc-500">
          아직 과목이 없습니다. 과목을 만들고 강의 자료와 녹음을 모아 두세요.
        </p>
      )}

      <ul className="flex flex-col gap-2">
        {courses?.map((course) => (
          <li key={course.courseId} className="flex items-center gap-2">
            <Link
              className="flex-1 rounded border px-3 py-2 hover:bg-zinc-50"
              href={`/courses/${course.courseId}`}
            >
              {course.title}
            </Link>
            <button
              type="button"
              className="rounded border px-2 py-2 text-xs text-red-600 disabled:opacity-50"
              disabled={deleting === course.courseId}
              onClick={() => remove(course)}
            >
              {deleting === course.courseId ? "삭제 중" : "삭제"}
            </button>
          </li>
        ))}
      </ul>
    </main>
  );
}
