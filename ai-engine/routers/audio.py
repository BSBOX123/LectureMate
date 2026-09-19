"""실시간 스트림 청크 STT 및 배치 정밀 분석 라우터.

- SPEC §2.2-2: POST /ai/v1/lectures/{lecture_id}/analyze-batch
- SPEC §2.2-5: WS   /ai/v1/lectures/{lecture_id}/audio-stream
"""

from fastapi import APIRouter

router = APIRouter(tags=["audio"])
