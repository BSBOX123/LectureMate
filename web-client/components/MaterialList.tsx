"use client";

import { useRef, useState } from "react";
import { materialApi } from "@/lib/api";
import type { MaterialResponse } from "@/types/api";

interface MaterialListProps {
  courseId: number;
  materials: MaterialResponse[];
  selectedId: number | null;
  onSelect: (materialId: number) => void;
  /** 업로드·삭제·재시도 후 목록을 다시 읽게 한다 */
  onChanged: () => void;
}

/** 자료 상태를 한국어 한 마디로. READY 는 굳이 표시하지 않는다. */
function statusLabel(material: MaterialResponse): string | null {
  switch (material.status) {
    case "PROCESSING":
      return "읽는 중";
    case "FAILED":
      return "실패";
    default:
      return material.totalPages !== null ? `${material.totalPages}쪽` : null;
  }
}

/** 과목에 속한 PDF 자료 목록과 업로드 (SPEC §2.1-4 ~ §2.1-6). */
export default function MaterialList({
  courseId,
  materials,
  selectedId,
  onSelect,
  onChanged,
}: MaterialListProps) {
  const fileInput = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const upload = (file: File) => {
    setUploading(true);
    setError(null);
    // 파일 이름에서 확장자만 떼어 제목으로 쓴다. 대부분 그대로 쓸 만하다.
    const title = file.name.replace(/\.pdf$/i, "").slice(0, 255);
    void materialApi
      .upload(courseId, title, file)
      .then(onChanged)
      .catch((cause: unknown) =>
        setError(cause instanceof Error ? cause.message : "업로드에 실패했습니다."),
      )
      .finally(() => {
        setUploading(false);
        if (fileInput.current) {
          fileInput.current.value = "";
        }
      });
  };

  const remove = (material: MaterialResponse) => {
    if (!window.confirm(`"${material.title}" 자료를 삭제할까요?`)) {
      return;
    }
    void materialApi
      .remove(courseId, material.materialId)
      .then(onChanged)
      .catch((cause: unknown) =>
        setError(cause instanceof Error ? cause.message : "삭제하지 못했습니다."),
      );
  };

  const retry = (material: MaterialResponse) => {
    void materialApi
      .retry(courseId, material.materialId)
      .then(onChanged)
      .catch((cause: unknown) =>
        setError(cause instanceof Error ? cause.message : "다시 시도하지 못했습니다."),
      );
  };

  return (
    <section className="flex min-h-0 flex-col gap-1">
      <h2 className="text-xs font-semibold text-zinc-500">강의 자료</h2>
      <ul className="flex min-h-0 flex-col gap-1 overflow-y-auto">
        {materials.length === 0 && (
          <li className="py-1 text-xs text-zinc-400">PDF를 올려 주세요.</li>
        )}
        {materials.map((material) => {
          const label = statusLabel(material);
          return (
            <li key={material.materialId} className="group flex items-center gap-1">
              <button
                type="button"
                className={`flex-1 truncate rounded px-2 py-1 text-left text-xs ${
                  material.materialId === selectedId
                    ? "bg-zinc-900 text-white"
                    : "hover:bg-zinc-100"
                }`}
                title={material.title}
                disabled={material.status !== "READY"}
                onClick={() => onSelect(material.materialId)}
              >
                {material.title}
                {label && (
                  <span
                    className={
                      material.materialId === selectedId
                        ? "ml-1 text-zinc-300"
                        : "ml-1 text-zinc-400"
                    }
                  >
                    {label}
                  </span>
                )}
              </button>
              {material.status === "FAILED" && (
                <button
                  type="button"
                  className="text-xs text-blue-600"
                  onClick={() => retry(material)}
                >
                  재시도
                </button>
              )}
              <button
                type="button"
                className="px-1 text-xs text-zinc-300 hover:text-red-600 group-hover:text-zinc-500"
                title="삭제"
                onClick={() => remove(material)}
              >
                ×
              </button>
            </li>
          );
        })}
      </ul>

      <label className="mt-1 cursor-pointer rounded border border-dashed px-2 py-1 text-center text-xs text-zinc-500 hover:bg-zinc-50">
        {uploading ? "업로드 중..." : "+ PDF 추가"}
        <input
          ref={fileInput}
          className="hidden"
          type="file"
          accept="application/pdf"
          disabled={uploading}
          onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) {
              upload(file);
            }
          }}
        />
      </label>
      {error && <p className="text-xs text-red-600">{error}</p>}
    </section>
  );
}
