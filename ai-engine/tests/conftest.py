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
