"""llm_client 테스트. 실제 Claude Code 실행 없이 stream-json 파싱과 명령 구성을 검증한다."""

import json

import pytest

from services import llm_client
from services.llm_client import LlmError, _claude_command, _text_delta, complete


def stream_event(delta_type: str, text: str) -> str:
    return json.dumps(
        {"type": "stream_event", "event": {"type": "content_block_delta",
                                           "delta": {"type": delta_type, "text": text}}}
    )


def test_extracts_text_delta_only():
    assert _text_delta(stream_event("text_delta", "다익")) == "다익"


def test_ignores_thinking_and_other_events():
    # 사고 과정은 답변이 아니다
    thinking = json.dumps(
        {"type": "stream_event", "event": {"type": "content_block_delta",
                                           "delta": {"type": "thinking_delta", "thinking": "..."}}}
    )
    assert _text_delta(thinking) is None
    assert _text_delta(json.dumps({"type": "system", "subtype": "init"})) is None
    assert _text_delta("not json") is None
    assert _text_delta("") is None


def test_command_blocks_tools_and_sets_system_prompt():
    command = _claude_command("너는 도우미다")

    assert command[1] == "-p"
    # 도구는 전부 차단한다 (파일 읽기·실행 방지)
    assert "--allowed-tools" in command and command[command.index("--allowed-tools") + 1] == ""
    assert command[command.index("--system-prompt") + 1] == "너는 도우미다"
    assert "--strict-mcp-config" in command
    assert command[command.index("--output-format") + 1] == "stream-json"


def test_complete_joins_streamed_tokens(monkeypatch):
    monkeypatch.setattr(llm_client, "stream", lambda system, user: iter(["벨만 ", "포드"]))

    assert complete("시스템", "질문") == "벨만 포드"


def test_claude_failure_raises(monkeypatch):
    """명령이 실패하면 LlmError 로 올려 호출 측이 실패를 감지하게 한다."""

    class FakeProcess:
        returncode = 1
        stdin = type("W", (), {"write": lambda self, _: None, "close": lambda self: None})()
        stdout = iter([])
        stderr = type("R", (), {"read": lambda self: "claude: not logged in"})()

        def wait(self, timeout=None):
            return 1

    monkeypatch.setattr(llm_client.subprocess, "Popen", lambda *a, **k: FakeProcess())
    monkeypatch.setattr(llm_client.settings, "llm_provider", "claude-code")

    with pytest.raises(LlmError, match="not logged in"):
        list(llm_client.stream("시스템", "질문"))
