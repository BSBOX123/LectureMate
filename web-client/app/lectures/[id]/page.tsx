"use client";

import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { lectureApi } from "@/lib/api";
import type { LectureResponse, PageAnnotationResponse } from "@/types/api";
import AnnotationOverlay from "@/components/AnnotationOverlay";
import AudioRecorder from "@/components/AudioRecorder";
import LectureChatPanel from "@/components/LectureChatPanel";
import PdfViewer from "@/components/PdfViewer";
import SlideTimeline from "@/components/SlideTimeline";

/**
 * 메인 강의 학습 대시보드.
 *
 * 현재 슬라이드 번호를 여기서 관리하고 PdfViewer, SlideTimeline, LectureChatPanel(출처 뱃지)이 공유한다.
 */
export default function LectureDashboardPage() {
  const params = useParams<{ id: string }>();
  const lectureId = Number(params.id);
  const [currentPage, setCurrentPage] = useState(1);
  const [lecture, setLecture] = useState<LectureResponse | null>(null);
  const [pdfObjectUrl, setPdfObjectUrl] = useState<string | null>(null);
  const [totalPages, setTotalPages] = useState<number | null>(null);
  const [scale, setScale] = useState(1);

  const handleDocumentLoaded = useCallback((pages: number) => setTotalPages(pages), []);
  const handlePageRendered = useCallback(
    (info: { scale: number }) => setScale(info.scale),
    [],
  );

  const [reloadKey, setReloadKey] = useState(0);
  const [recording, setRecording] = useState(false);
  const [analyzeError, setAnalyzeError] = useState<string | null>(null);
  const [annotation, setAnnotation] = useState<PageAnnotationResponse | null>(null);

  useEffect(() => {
    if (!Number.isFinite(lectureId)) {
      return;
    }
    let cancelled = false;
    void lectureApi.get(lectureId).then((loaded) => {
      if (!cancelled) {
        setLecture(loaded);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [lectureId, reloadKey]);

  // 상태가 바뀌는 동안(파싱 중, 녹음 중) 주기적으로 다시 확인한다.
  // 서버가 RECORDING 으로 바꾸는 시점이 WebSocket open 직후라 한 번만 조회하면 놓칠 수 있다.
  useEffect(() => {
    const inProgress =
      recording ||
      lecture?.status === "PROCESSING" ||
      lecture?.status === "RECORDING" ||
      lecture?.status === "ANALYZING";
    if (!inProgress) {
      return;
    }
    const timer = setInterval(() => setReloadKey((key) => key + 1), 2000);
    return () => clearInterval(timer);
  }, [lecture?.status, recording]);

  // 현재 슬라이드의 자동 필기 (분석 전이면 404 → null)
  useEffect(() => {
    if (!Number.isFinite(lectureId)) {
      return;
    }
    let cancelled = false;
    void lectureApi
      .annotations(lectureId, currentPage)
      .then((loaded) => {
        if (!cancelled) {
          setAnnotation(loaded);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setAnnotation(null);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [lectureId, currentPage, lecture?.status, reloadKey]);

  // PDF 는 인증이 필요하므로 blob URL 로 받아 둔다
  useEffect(() => {
    const pdfUrl = lecture?.pdfUrl;
    if (!pdfUrl) {
      return;
    }
    let revoke: string | null = null;
    void lectureApi.pdfObjectUrl(pdfUrl).then((objectUrl) => {
      revoke = objectUrl;
      setPdfObjectUrl(objectUrl);
    });
    return () => {
      if (revoke) {
        URL.revokeObjectURL(revoke);
      }
    };
  }, [lecture?.pdfUrl]);

  return (
    <div className="flex h-screen flex-col">
      <header className="flex items-center justify-between border-b px-4 py-2">
        <h1 className="font-semibold">
          {lecture?.title ?? `강의 #${lectureId}`}
          {lecture && (
            <span className="ml-2 text-xs font-normal text-zinc-500">{lecture.status}</span>
          )}
        </h1>
        {lecture?.audioUrl && lecture.status === "READY" && (
          <button
            type="button"
            className="rounded border px-3 py-1 text-sm"
            onClick={() => {
              setAnalyzeError(null);
              void lectureApi
                .finishRecording(lectureId)
                .then(() => setReloadKey((key) => key + 1))
                .catch((cause: unknown) =>
                  setAnalyzeError(
                    cause instanceof Error ? cause.message : "분석을 시작하지 못했습니다.",
                  ),
                );
            }}
          >
            정밀 분석 시작
          </button>
        )}
        {analyzeError && <p className="text-sm text-red-600">{analyzeError}</p>}
        <AudioRecorder
          lectureId={lectureId}
          onRecordingStarted={() => setRecording(true)}
          onRecordingFinished={() => {
            setRecording(false);
            setReloadKey((key) => key + 1);
          }}
        />
      </header>
      <main className="grid flex-1 grid-cols-[12rem_1fr_22rem] gap-2 overflow-hidden p-2">
        <SlideTimeline
          totalPages={totalPages}
          items={[]}
          currentPage={currentPage}
          onSelectPage={setCurrentPage}
        />
        <PdfViewer
          pdfUrl={pdfObjectUrl}
          pageNumber={currentPage}
          onPageChange={setCurrentPage}
          onDocumentLoaded={handleDocumentLoaded}
          onPageRendered={handlePageRendered}
        >
          <AnnotationOverlay annotation={annotation} scale={scale} />
        </PdfViewer>
        <LectureChatPanel
          lectureId={lectureId}
          onCitationClick={setCurrentPage}
        />
      </main>
    </div>
  );
}
