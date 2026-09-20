"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { lectureApi } from "@/lib/api";

/** 강의 생성 및 PDF 업로드 화면 (SPEC §2.1-1). */
export default function NewLecturePage() {
  const router = useRouter();
  const [title, setTitle] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!file) {
      return;
    }
    setUploading(true);
    setError(null);
    try {
      const lecture = await lectureApi.create(title, file);
      router.push(`/lectures/${lecture.lectureId}`);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "업로드에 실패했습니다.");
    } finally {
      setUploading(false);
    }
  };

  return (
    <main className="mx-auto flex w-full max-w-md flex-1 flex-col justify-center gap-4 p-6">
      <h1 className="text-xl font-semibold">새 강의 만들기</h1>
      <form className="flex flex-col gap-3" onSubmit={handleSubmit}>
        <input
          className="rounded border px-3 py-2"
          type="text"
          required
          maxLength={255}
          placeholder="강의 제목 (예: 컴퓨터 알고리즘 5강)"
          value={title}
          onChange={(event) => setTitle(event.target.value)}
        />
        <input
          className="rounded border px-3 py-2 text-sm"
          type="file"
          required
          accept="application/pdf"
          onChange={(event) => setFile(event.target.files?.[0] ?? null)}
        />
        <p className="text-xs text-zinc-500">PDF만 업로드할 수 있고 최대 50MB입니다.</p>
        {error && <p className="text-sm text-red-600">{error}</p>}
        <button
          className="rounded bg-zinc-900 px-3 py-2 text-white disabled:opacity-50"
          type="submit"
          disabled={uploading}
        >
          {uploading ? "업로드 중..." : "업로드"}
        </button>
      </form>
    </main>
  );
}
