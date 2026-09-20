"""LLM 호출 추상화.

두 가지 백엔드를 지원한다 (`LLM_PROVIDER`):

- `claude-code`: 로컬에 설치된 Claude Code CLI 를 headless(`-p`) 로 실행한다.
  구독 사용량을 쓰며 별도 API 키가 필요 없다. 품질이 높아 기본값으로 쓴다.
- `ollama`: OpenAI 호환 엔드포인트(Ollama/vLLM). Claude Code 를 쓸 수 없을 때의 대체 수단.

RAG 검색과 컨텍스트 구성은 이 모듈 밖(rag_service)에서 그대로 수행한다.
여기서 바뀌는 것은 "생성" 단계뿐이다.
"""

import json
import logging
import subprocess
import tempfile
from collections.abc import Iterator

from core.config import settings

log = logging.getLogger(__name__)


class LlmError(RuntimeError):
    """LLM 호출 실패."""


def complete(system_prompt: str, user_prompt: str) -> str:
    """한 번에 전체 답변을 받는다 (자동 필기 생성용)."""
    return "".join(stream(system_prompt, user_prompt))


def stream(system_prompt: str, user_prompt: str) -> Iterator[str]:
    """토큰 조각을 순서대로 내보낸다 (채팅용)."""
    if settings.llm_provider == "claude-code":
        yield from _stream_claude_code(system_prompt, user_prompt)
    else:
        yield from _stream_openai_compatible(system_prompt, user_prompt)


# --- Claude Code (headless) -------------------------------------------------


def _claude_command(system_prompt: str) -> list[str]:
    return [
        settings.claude_code_command,
        "-p",
        # 강의 질의응답에 도구는 필요 없다. 파일 읽기·실행 등을 원천 차단한다.
        "--allowed-tools",
        "",
        "--system-prompt",
        system_prompt,
        "--output-format",
        "stream-json",
        "--include-partial-messages",
        "--verbose",
        # 프로젝트 설정(MCP 서버 등)을 끌어오지 않는다
        "--strict-mcp-config",
        *(["--model", settings.claude_code_model] if settings.claude_code_model else []),
    ]


def _stream_claude_code(system_prompt: str, user_prompt: str) -> Iterator[str]:
    """`claude -p` 의 stream-json 출력에서 텍스트 델타만 뽑아낸다."""
    # 프로젝트 디렉토리에서 실행하면 CLAUDE.md·훅이 딸려 온다. 빈 임시 디렉토리에서 돌린다.
    with tempfile.TemporaryDirectory() as workdir:
        process = subprocess.Popen(  # noqa: S603 - 명령은 설정값으로 고정되어 있다
            _claude_command(system_prompt),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            cwd=workdir,
            text=True,
            encoding="utf-8",
        )
        assert process.stdin is not None and process.stdout is not None
        process.stdin.write(user_prompt)
        process.stdin.close()

        produced = False
        for line in process.stdout:
            delta = _text_delta(line)
            if delta:
                produced = True
                yield delta

        process.wait(timeout=settings.llm_timeout_seconds)
        if process.returncode != 0 or not produced:
            stderr = (process.stderr.read() if process.stderr else "")[:500]
            raise LlmError(
                f"claude 실행 실패 (code={process.returncode}): {stderr or '응답이 비어 있습니다'}"
            )


def _text_delta(line: str) -> str | None:
    """stream-json 한 줄에서 assistant 텍스트 델타를 뽑는다. 그 외 이벤트는 무시한다."""
    line = line.strip()
    if not line.startswith("{"):
        return None
    try:
        payload = json.loads(line)
    except json.JSONDecodeError:
        return None
    if payload.get("type") != "stream_event":
        return None
    event = payload.get("event", {})
    if event.get("type") != "content_block_delta":
        return None
    delta = event.get("delta", {})
    # thinking_delta 는 사용자에게 보여 줄 답변이 아니다
    return delta.get("text") if delta.get("type") == "text_delta" else None


# --- OpenAI 호환 (Ollama / vLLM) -------------------------------------------


def _stream_openai_compatible(system_prompt: str, user_prompt: str) -> Iterator[str]:
    from openai import OpenAI

    client = OpenAI(
        base_url=settings.llm_backend_url,
        api_key="not-needed",
        timeout=settings.llm_timeout_seconds,
    )
    response = client.chat.completions.create(
        model=settings.llm_model_name,
        messages=[
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        temperature=0.3,
        stream=True,
    )
    for chunk in response:
        content = chunk.choices[0].delta.content if chunk.choices else None
        if content:
            yield content
