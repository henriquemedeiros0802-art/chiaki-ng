"""Backends de TTS com streaming e clonagem de voz."""
from __future__ import annotations

import asyncio
import hashlib
import logging
import math
from typing import AsyncIterator, Optional, Protocol

import httpx
import numpy as np

from .config import Settings

log = logging.getLogger("dub.tts")


class TTSError(RuntimeError):
    pass


class TTSBackend(Protocol):
    sample_rate: int

    def stream(self, text: str, voice_id: str) -> AsyncIterator[bytes]: ...
    async def clone(self, name: str, wav: bytes) -> str: ...
    async def delete_voice(self, voice_id: str) -> None: ...
    def fallback_voice(self, gender: str) -> str: ...
    async def warmup(self) -> None: ...
    async def close(self) -> None: ...


class ElevenLabsTTS:
    BASE = "https://api.elevenlabs.io"

    def __init__(self, cfg: Settings):
        if not cfg.elevenlabs_key:
            raise TTSError("ELEVENLABS_API_KEY não definido")
        self.cfg = cfg
        self.sample_rate = cfg.output_rate
        # Conexão mantida aberta (keep-alive) para não pagar handshake TLS a cada fala.
        self.client = httpx.AsyncClient(
            base_url=self.BASE,
            headers={"xi-api-key": cfg.elevenlabs_key},
            timeout=httpx.Timeout(30.0, connect=5.0),
            limits=httpx.Limits(max_keepalive_connections=4, keepalive_expiry=120),
        )

    def fallback_voice(self, gender: str) -> str:
        return {"male": self.cfg.voice_male, "female": self.cfg.voice_female}.get(gender, self.cfg.voice_neutral)

    async def warmup(self) -> None:
        try:
            await self.client.get("/v1/models")
        except httpx.HTTPError as e:
            log.warning("Warm-up falhou: %s", e)

    async def stream(self, text: str, voice_id: str) -> AsyncIterator[bytes]:
        body = {
            "text": text,
            "model_id": self.cfg.model_id,
            "language_code": self.cfg.language,
            "voice_settings": {"stability": 0.4, "similarity_boost": 0.85,
                               "speed": max(0.7, min(1.2, self.cfg.speed))},
        }
        async with self.client.stream("POST", f"/v1/text-to-speech/{voice_id}/stream",
                                      params={"output_format": self.cfg.output_format}, json=body) as r:
            if r.status_code != 200:
                detail = (await r.aread()).decode("utf-8", "replace")[:300]
                raise TTSError(f"ElevenLabs {r.status_code}: {detail}")
            carry = b""
            async for chunk in r.aiter_bytes():
                data = carry + chunk
                if len(data) % 2:               # PCM16: nunca entregar meia amostra
                    carry, data = data[-1:], data[:-1]
                else:
                    carry = b""
                if data:
                    yield data

    async def clone(self, name: str, wav: bytes) -> str:
        r = await self.client.post("/v1/voices/add", data={"name": name},
                                   files=[("files", ("sample.wav", wav, "audio/wav"))])
        if r.status_code != 200:
            raise TTSError(f"Clonagem falhou {r.status_code}: {r.text[:300]}")
        return r.json()["voice_id"]

    async def delete_voice(self, voice_id: str) -> None:
        try:
            await self.client.delete(f"/v1/voices/{voice_id}")
        except httpx.HTTPError:
            log.warning("Não consegui apagar a voz antiga %s", voice_id)

    async def close(self) -> None:
        await self.client.aclose()


class FakeTTS:
    """Gera bipes (tom por voz). Para testar o app e o pipeline sem chave nem custo."""

    def __init__(self, sample_rate: int = 24000, ms_per_word: int = 280, chunk_ms: int = 40, realtime: bool = True):
        self.sample_rate = sample_rate
        self.ms_per_word = ms_per_word
        self.chunk_ms = chunk_ms
        self.realtime = realtime
        self.cloned: list[str] = []
        self.deleted: list[str] = []

    def fallback_voice(self, gender: str) -> str:
        return f"fake-{gender}"

    @staticmethod
    def _freq(voice_id: str) -> float:
        h = int(hashlib.sha1(voice_id.encode()).hexdigest()[:4], 16)
        return 180.0 + (h % 400)

    async def stream(self, text: str, voice_id: str) -> AsyncIterator[bytes]:
        words = max(1, len(text.split()))
        total = int(self.sample_rate * self.ms_per_word * words / 1000)
        step = int(self.sample_rate * self.chunk_ms / 1000)
        f = self._freq(voice_id)
        for start in range(0, total, step):
            n = min(step, total - start)
            t = (np.arange(start, start + n) / self.sample_rate)
            x = 0.25 * np.sin(2 * math.pi * f * t)
            yield (x * 32767).astype("<i2").tobytes()
            if self.realtime:
                await asyncio.sleep(self.chunk_ms / 1000 * 0.5)   # gera 2x mais rápido que o tempo real
            else:
                await asyncio.sleep(0)

    async def clone(self, name: str, wav: bytes) -> str:
        vid = f"fake-clone-{len(self.cloned) + 1}"
        self.cloned.append(vid)
        return vid

    async def delete_voice(self, voice_id: str) -> None:
        self.deleted.append(voice_id)

    async def warmup(self) -> None:
        return None

    async def close(self) -> None:
        return None


def make_tts(cfg: Settings) -> TTSBackend:
    if cfg.tts_backend == "fake":
        return FakeTTS(sample_rate=cfg.output_rate)
    return ElevenLabsTTS(cfg)
