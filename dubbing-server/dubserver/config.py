"""Configuração via variáveis de ambiente."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _f(name: str, default: float) -> float:
    return float(os.environ.get(name, default))


@dataclass
class Settings:
    # Segurança: se vazio, o servidor aceita qualquer cliente (use só em rede local).
    token: str = field(default_factory=lambda: os.environ.get("DUB_TOKEN", ""))

    # Backend de TTS: "elevenlabs" ou "fake" (bipes, para testar sem chave).
    tts_backend: str = field(default_factory=lambda: os.environ.get("DUB_TTS", "elevenlabs"))
    elevenlabs_key: str = field(default_factory=lambda: os.environ.get("ELEVENLABS_API_KEY", ""))
    model_id: str = field(default_factory=lambda: os.environ.get("DUB_MODEL", "eleven_flash_v2_5"))
    output_format: str = field(default_factory=lambda: os.environ.get("DUB_OUTPUT_FORMAT", "pcm_24000"))
    language: str = field(default_factory=lambda: os.environ.get("DUB_LANGUAGE", "pt"))
    speed: float = field(default_factory=lambda: _f("DUB_SPEED", 1.0))

    # Vozes de reserva usadas até a voz clonada ficar pronta (IDs do ElevenLabs).
    voice_male: str = field(default_factory=lambda: os.environ.get("DUB_VOICE_MALE", "pNInz6obpgDQGcFmaJgB"))
    voice_female: str = field(default_factory=lambda: os.environ.get("DUB_VOICE_FEMALE", "21m00Tcm4TlvDq8ikWAM"))
    voice_neutral: str = field(default_factory=lambda: os.environ.get("DUB_VOICE_NEUTRAL", "pNInz6obpgDQGcFmaJgB"))

    # Clonagem: segundos de fala acumulados antes de criar a voz, e antes de recriá-la.
    min_clone_seconds: float = field(default_factory=lambda: _f("DUB_MIN_CLONE_S", 12.0))
    reclone_seconds: float = field(default_factory=lambda: _f("DUB_RECLONE_S", 40.0))
    max_sample_seconds: float = 60.0

    # Identificação de falante.
    embedder: str = field(default_factory=lambda: os.environ.get("DUB_EMBEDDER", "auto"))
    match_threshold: float = field(default_factory=lambda: _f("DUB_MATCH_THRESHOLD", 0.0))  # 0 = padrão do embedder
    max_speakers: int = 12

    data_dir: Path = field(default_factory=lambda: Path(os.environ.get("DUB_DATA", "./data")))

    @property
    def output_rate(self) -> int:
        # "pcm_24000" -> 24000
        try:
            return int(self.output_format.split("_")[1])
        except (IndexError, ValueError):
            return 24000
