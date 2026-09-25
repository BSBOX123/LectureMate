"""FastAPI -> Spring Boot 전사 완료 Webhook (SPEC §2.2-4)."""

import logging

import httpx

from core.config import settings

log = logging.getLogger(__name__)


async def notify_transcription_complete(
    recording_id: int,
    status: str,
    segment_count: int,
    duration_ms: int,
) -> None:
    """전사 결과를 Spring Boot 에 알린다. 실패해도 예외를 올리지 않고 로그만 남긴다."""
    url = f"{settings.spring_boot_webhook_url}/recordings/{recording_id}/transcription-complete"
    payload = {
        "recordingId": recording_id,
        "status": status,
        "segmentCount": segment_count,
        "durationMs": duration_ms,
    }
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            response = await client.post(
                url, json=payload, headers={"X-Internal-Secret": settings.internal_api_secret}
            )
            response.raise_for_status()
    except httpx.HTTPError as e:
        log.error("Webhook 전송 실패 recording_id=%s: %s", recording_id, e)
