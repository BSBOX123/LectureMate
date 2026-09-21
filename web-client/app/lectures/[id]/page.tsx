"use client";

import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { lectureApi } from "@/lib/api";
import type {
  LectureResponse,
  PageAnnotationResponse,
  SlideTimelineResponse,
} from "@/types/api";
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
  const [analyzeStartedAt, setAnalyzeStartedAt] = useState<number | null>(null);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);
  const [annotation, setAnnotation] = useState<PageAnnotationResponse | null>(null);
  const [timeline, setTimeline] = useState<SlideTimelineResponse[]>([]);

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

  // 슬라이드별 발화 분량과 시험 힌트 (분석 전이면 빈 배열)
  useEffect(() => {
    if (!Number.isFinite(lectureId)) {
      return;
    }
    let cancelled = false;
    void lectureApi
      .timeline(lectureId)
      .then((loaded) => {
        if (!cancelled) {
          setTimeline(loaded);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setTimeline([]);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [lectureId, lecture?.status, reloadKey]);

  // 분석 중 경과 시간 (몇 분 걸리는 작업이라 멈춘 것처럼 보이지 않게 한다)
  useEffect(() => {
    if (lecture?.status !== "ANALYZING" || analyzeStartedAt === null) {
      return;
    }
    const timer = setInterval(
      () => setElapsedSeconds(Math.floor((Date.now() - analyzeStartedAt) / 1000)),
      1000,
    );
    return () => clearInterval(timer);
  }, [lecture?.status, analyzeStartedAt]);

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

  const startAnalysis = () => {
    setAnalyzeError(null);
    setAnalyzeStartedAt(Date.now());
    setElapsedSeconds(0);
    void lectureApi
      .finishRecording(lectureId)
      .then(() => setReloadKey((key) => key + 1))
      .catch((cause: unknown) =>
        setAnalyzeError(cause instanceof Error ? cause.message : "분석을 시작하지 못했습니다."),
      );
  };

  const retryAnalysis = () => {
    setAnalyzeError(null);
    setAnalyzeStartedAt(Date.now());
    void lectureApi
      .retry(lectureId)
      .then(() => setReloadKey((key) => key + 1))
      .catch((cause: unknown) =>
        setAnalyzeError(cause instanceof Error ? cause.message : "다시 시도하지 못했습니다."),
      );
  };

  // 녹음은 있는데 아직 분석하지 않은 상태인지 (타임라인이 비어 있으면 전사 결과가 없다는 뜻)
  const needsAnalysis =
    lecture?.status === "READY" && lecture.audioUrl !== null && timeline.length === 0;
  const elapsedText = `${Math.floor(elapsedSeconds / 60)}분 ${elapsedSeconds % 60}초 경과`;

  const analysisBanner = (
    <>
      {needsAnalysis && (
        <div className="flex items-center justify-between gap-3 border-b bg-amber-50 px-4 py-2 text-sm">
          <p className="text-amber-900">
            녹음이 저장되었습니다. <strong>정밀 분석</strong>을 시작하면 교수님 말씀을 글로 옮기고
            슬라이드별 필기를 만듭니다. 36분 강의 기준 약 10분 걸립니다.
          </p>
          <button
            type="button"
            className="shrink-0 rounded bg-amber-600 px-3 py-1 font-medium text-white"
            onClick={startAnalysis}
          >
            정밀 분석 시작
          </button>
        </div>
      )}
      {lecture?.status === "ANALYZING" && (
        <div className="border-b bg-blue-50 px-4 py-2 text-sm text-blue-900">
          <strong>분석 중입니다.</strong>{" "}
          {timeline.length === 0
            ? "음성을 글로 옮기는 중입니다 (가장 오래 걸리는 단계)."
            : `슬라이드별 필기를 만드는 중입니다 (발화가 매칭된 슬라이드 ${timeline.length}쪽).`}{" "}
          {analyzeStartedAt !== null && <span className="text-blue-700">{elapsedText}</span>}
          <span className="ml-1 text-blue-700">창을 닫아도 서버에서 계속 진행됩니다.</span>
        </div>
      )}
      {lecture?.status === "FAILED" && (
        <div className="flex items-center justify-between gap-3 border-b bg-red-50 px-4 py-2 text-sm">
          <p className="text-red-900">분석에 실패했습니다. 다시 시도할 수 있습니다.</p>
          <button
            type="button"
            className="shrink-0 rounded border border-red-300 bg-white px-3 py-1 text-red-700"
            onClick={retryAnalysis}
          >
            분석 다시 시도
          </button>
        </div>
      )}
      {analyzeError && (
        <p className="border-b bg-red-50 px-4 py-2 text-sm text-red-700">{analyzeError}</p>
      )}
    </>
  );

  return (
    <div className="flex h-screen flex-col">
      <header className="flex items-center justify-between border-b px-4 py-2">
        <h1 className="font-semibold">
          {lecture?.title ?? `강의 #${lectureId}`}
          {lecture && (
            <span className="ml-2 text-xs font-normal text-zinc-500">{lecture.status}</span>
          )}
        </h1>
                <AudioRecorder
          lectureId={lectureId}
          onRecordingStarted={() => setRecording(true)}
          onRecordingFinished={() => {
            setRecording(false);
            setReloadKey((key) => key + 1);
          }}
        />
      </header>
      {analysisBanner}
      <main className="grid flex-1 grid-cols-[12rem_1fr_22rem] gap-2 overflow-hidden p-2">
        <SlideTimeline
          totalPages={totalPages}
          items={timeline}
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
