"use client";

import { useParams } from "next/navigation";
import { useState } from "react";
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

  return (
    <div className="flex h-screen flex-col">
      <header className="flex items-center justify-between border-b px-4 py-2">
        <h1 className="font-semibold">강의 #{lectureId}</h1>
        <AudioRecorder lectureId={lectureId} />
      </header>
      <main className="grid flex-1 grid-cols-[12rem_1fr_22rem] gap-2 overflow-hidden p-2">
        <SlideTimeline
          items={[]}
          currentPage={currentPage}
          onSelectPage={setCurrentPage}
        />
        <PdfViewer
          pdfUrl={null}
          pageNumber={currentPage}
          onPageChange={setCurrentPage}
        >
          <AnnotationOverlay annotation={null} scale={1} />
        </PdfViewer>
        <LectureChatPanel
          lectureId={lectureId}
          onCitationClick={setCurrentPage}
        />
      </main>
    </div>
  );
}
