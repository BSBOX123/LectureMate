"""BAAI/bge-m3 임베딩 생성 (SPEC §5.1 EMBEDDING_MODEL_NAME).

`EMBEDDING_ENABLED=false`(기본)이면 모델을 내려받지 않고 None 을 반환한다.
"""

from functools import lru_cache

from core.config import settings


@lru_cache(maxsize=1)
def _load_model():
    from sentence_transformers import SentenceTransformer

    return SentenceTransformer(
        settings.embedding_model_name,
        cache_folder=str(settings.model_cache_dir) if settings.model_cache_dir else None,
    )


def embed_texts(texts: list[str]) -> list[list[float] | None]:
    """텍스트 목록을 1024차원 벡터로 변환한다. 비활성화 상태면 None 목록을 반환한다."""
    if not settings.embedding_enabled:
        return [None] * len(texts)
    vectors = _load_model().encode(texts, normalize_embeddings=True)
    return [vector.tolist() for vector in vectors]
