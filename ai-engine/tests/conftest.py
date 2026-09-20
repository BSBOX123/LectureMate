"""테스트는 전용 DB(lecturemate_test)를 사용한다.

core.config 가 import 시점에 환경 변수를 읽으므로 그 전에 설정해야 한다.
"""

import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

os.environ.setdefault(
    "DATABASE_URL",
    "postgresql+asyncpg://postgres:postgres@localhost:5432/lecturemate_test",
)

# 테스트는 bge-m3 모델(약 2GB)을 내려받지 않는다.
# 임베딩이 필요한 테스트는 embed_texts 를 직접 대체한다.
os.environ.setdefault("EMBEDDING_ENABLED", "false")
