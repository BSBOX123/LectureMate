"""FastAPI -> Spring Boot 배치 완료 Webhook (SPEC §2.2-4)."""

import logging

import httpx

from core.config import settings

log = logging.getLogger(__name__)


async def notify_analysis_complete(
    lecture_id: int,
    status: str,
    total_pages_analyzed: int,
    matched_transcript_segments: int,
) -> None:
    """분석 결과를 Spring Boot 에 알린다. 실패해도 예외를 올리지 않고 로그만 남긴다."""
    url = f"{settings.spring_boot_webhook_url}/lectures/{lecture_id}/analysis-complete"
    payload = {
        "lectureId": lecture_id,
        "status": status,
        "totalPagesAnalyzed": total_pages_analyzed,
        "matchedTranscriptSegments": matched_transcript_segments,
    }
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            response = await client.post(
                url, json=payload, headers={"X-Internal-Secret": settings.internal_api_secret}
            )
            response.raise_for_status()
    except httpx.HTTPError as e:
        log.error("Webhook 전송 실패 lecture_id=%s: %s", lecture_id, e)
