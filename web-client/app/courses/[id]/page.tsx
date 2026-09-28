"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { courseApi, fileObjectUrl, materialApi, recordingApi } from "@/lib/api";
import type { CourseResponse, MaterialResponse, RecordingResponse } from "@/types/api";
import AudioRecorder from "@/components/AudioRecorder";
import CourseChatPanel from "@/components/CourseChatPanel";
import MaterialList from "@/components/MaterialList";
import PdfViewer from "@/components/PdfViewer";
import RecordingList from "@/components/RecordingList";
import RecordingSummary from "@/components/RecordingSummary";

/**
 * 과목 학습 화면.
 *
 * 왼쪽에 자료·녹음 목록, 가운데에 선택한 PDF, 오른쪽에 질의응답을 둔다. 답변의 출처 뱃지를 누르면
 * 그 자료의 해당 쪽으로 넘어간다. 자료가 여러 개라 "어떤 자료의 몇 쪽"인지가 함께 필요하다.
 */
export default function CourseDashboardPage() {
  const params = useParams<{ id: string }>();
  const courseId = Number(params.id);

  const [course, setCourse] = useState<CourseResponse | null>(null);
  const [materials, setMaterials] = useState<MaterialResponse[]>([]);
  const [recordings, setRecordings] = useState<RecordingResponse[]>([]);
  const [selectedMaterialId, setSelectedMaterialId] = useState<number | null>(null);
  // 가운데 영역에 자료(PDF)를 띄울지, 녹음 요약을 띄울지. 요약은 읽는 문서라 PDF 자리를 쓴다
  const [summaryRecordingId, setSummaryRecordingId] = useState<number | null>(null);
  const [currentPage, setCurrentPage] = useState(1);
  const [loadedPdf, setLoadedPdf] = useState<{ pdfUrl: string; objectUrl: string } | null>(
    null,
  );
  const [reloadKey, setReloadKey] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(() => setReloadKey((key) => key + 1), []);
  const handleDocumentLoaded = useCallback(() => undefined, []);

  useEffect(() => {
    if (!Number.isFinite(courseId)) {
      return;
    }
    let cancelled = false;
    void courseApi
      .get(courseId)
      .then((loaded) => !cancelled && setCourse(loaded))
      .catch((cause: unknown) => {
        if (!cancelled) {
          setError(cause instanceof Error ? cause.message : "과목을 불러오지 못했습니다.");
        }
      });
    return () => {
      cancelled = true;
    };
  }, [courseId]);

  useEffect(() => {
    if (!Number.isFinite(courseId)) {
      return;
    }
    let cancelled = false;
    void Promise.all([materialApi.list(courseId), recordingApi.list(courseId)])
      .then(([loadedMaterials, loadedRecordings]) => {
        if (cancelled) {
          return;
        }
        setMaterials(loadedMaterials);
        setRecordings(loadedRecordings);
        // 아직 아무것도 고르지 않았으면 읽기가 끝난 첫 자료를 띄운다
        setSelectedMaterialId((previous) => {
          if (previous !== null && loadedMaterials.some((m) => m.materialId === previous)) {
            return previous;
          }
          return loadedMaterials.find((m) => m.status === "READY")?.materialId ?? null;
        });
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setError(cause instanceof Error ? cause.message : "목록을 불러오지 못했습니다.");
        }
      });
    return () => {
      cancelled = true;
    };
  }, [courseId, reloadKey]);

  // 파싱이나 전사가 진행 중일 때만 주기적으로 다시 확인한다
  useEffect(() => {
    const inProgress =
      materials.some((material) => material.status === "PROCESSING") ||
      recordings.some(
        (recording) => recording.status === "ANALYZING" || recording.status === "RECORDING",
      );
    if (!inProgress) {
      return;
    }
    const timer = setInterval(reload, 3000);
    return () => clearInterval(timer);
  }, [materials, recordings, reload]);

  const selectedMaterial = materials.find((item) => item.materialId === selectedMaterialId);
  const selectedPdfUrl =
    selectedMaterial?.status === "READY" ? (selectedMaterial.pdfUrl ?? null) : null;

  // PDF 는 인증이 필요하므로 blob URL 로 받아 둔다.
  // 어느 자료의 것인지 함께 담아 두고, 다른 자료를 고르면 렌더링에서 걸러 낸다.
  // (자료를 바꿀 때마다 effect 안에서 상태를 비우면 불필요한 렌더가 한 번 더 생긴다)
  useEffect(() => {
    if (!selectedPdfUrl) {
      return;
    }
    let revoke: string | null = null;
    let cancelled = false;
    void fileObjectUrl(selectedPdfUrl).then((objectUrl) => {
      if (cancelled) {
        URL.revokeObjectURL(objectUrl);
        return;
      }
      revoke = objectUrl;
      setLoadedPdf({ pdfUrl: selectedPdfUrl, objectUrl });
    });
    return () => {
      cancelled = true;
      if (revoke) {
        URL.revokeObjectURL(revoke);
      }
    };
  }, [selectedPdfUrl]);

  const pdfObjectUrl =
    loadedPdf && loadedPdf.pdfUrl === selectedPdfUrl ? loadedPdf.objectUrl : null;

  const selectMaterial = (materialId: number) => {
    setSelectedMaterialId(materialId);
    setCurrentPage(1);
    setSummaryRecordingId(null); // 자료를 고르면 PDF 로 돌아온다
  };

  const goToCitation = (materialId: number, pageNumber: number) => {
    setSelectedMaterialId(materialId);
    setCurrentPage(pageNumber);
    setSummaryRecordingId(null);
  };

  const summaryRecording =
    summaryRecordingId === null
      ? null
      : (recordings.find((item) => item.recordingId === summaryRecordingId) ?? null);

  return (
    <div className="flex h-screen flex-col">
      <header className="flex items-center justify-between border-b px-4 py-2">
        <h1 className="font-semibold">
          <Link className="text-zinc-400 hover:underline" href="/courses">
            내 과목
          </Link>
          <span className="mx-2 text-zinc-300">/</span>
          {course?.title ?? `과목 #${courseId}`}
        </h1>
        <AudioRecorder
          courseId={courseId}
          onRecordingStarted={reload}
          onRecordingFinished={reload}
        />
      </header>
      {error && <p className="border-b bg-red-50 px-4 py-2 text-sm text-red-700">{error}</p>}
      <main className="grid flex-1 grid-cols-[14rem_1fr_22rem] gap-2 overflow-hidden p-2">
        <aside className="flex min-h-0 flex-col gap-4 overflow-y-auto">
          <MaterialList
            courseId={courseId}
            materials={materials}
            selectedId={selectedMaterialId}
            onSelect={selectMaterial}
            onChanged={reload}
          />
          <RecordingList
            courseId={courseId}
            recordings={recordings}
            selectedId={summaryRecordingId}
            onShowSummary={setSummaryRecordingId}
            onChanged={reload}
          />
        </aside>
        {summaryRecording ? (
          <RecordingSummary
            courseId={courseId}
            recording={summaryRecording}
            onCreated={reload}
          />
        ) : (
          <PdfViewer
            pdfUrl={pdfObjectUrl}
            pageNumber={currentPage}
            onPageChange={setCurrentPage}
            onDocumentLoaded={handleDocumentLoaded}
          />
        )}
        <CourseChatPanel courseId={courseId} onCitationClick={goToCitation} />
      </main>
    </div>
  );
}
