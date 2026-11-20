"""Lógica de uma sessão de dublagem (uma conexão WebSocket).

Fluxo: o app envia sem parar o áudio do jogo (PCM16 mono 16 kHz). Quando uma legenda
aparece, chega {"type":"subtitle"}. O servidor descobre quem fala olhando os últimos
~1,5 s de áudio que JÁ estão no buffer (sem esperar upload), escolhe a voz do falante
(clonada ou de reserva) e começa a transmitir o PCM da dublagem assim que o TTS entrega
o primeiro pedaço. Quando a legenda some, chega {"type":"stop"} e tudo é cancelado.
"""
from __future__ import annotations

import asyncio
import logging
import time
from typing import Awaitable, Callable, Optional

import numpy as np

from .audio import EnergyVAD, FRAME, SAMPLE_RATE, pcm_to_float, to_wav_bytes, voiced_samples
from .config import Settings
from .speakers import Profile, Registry
from .tts import TTSBackend, TTSError

log = logging.getLogger("dub.session")

AUDIO_HEADER = b"\x01"          # byte de tipo dos frames binários servidor->app
RING_SECONDS = 8
WINDOW_SECONDS = 1.6
MIN_VOICED_SECONDS = 0.4
MAX_WAIT_SECONDS = 0.3

SendJson = Callable[[dict], Awaitable[None]]
SendBytes = Callable[[bytes], Awaitable[None]]


def frame_audio(utterance_id: int, pcm: bytes) -> bytes:
    return AUDIO_HEADER + utterance_id.to_bytes(4, "big") + pcm


class DubSession:
    def __init__(self, game: str, send_json: SendJson, send_bytes: SendBytes, tts: TTSBackend,
                 registry: Registry, cfg: Settings):
        self.game = game
        self.send_json = send_json
        self.send_bytes = send_bytes
        self.tts = tts
        self.registry = registry
        self.cfg = cfg

        self._ring = np.zeros(0, dtype=np.float32)
        self._vad = EnergyVAD()
        self._task: Optional[asyncio.Task] = None
        self._bg: set[asyncio.Task] = set()
        self.current_id = 0
        self.subtitle_active = False
        self.active_profile: Optional[Profile] = None
        self.last_profile: Optional[Profile] = None

    # ---------- entrada de áudio ----------
    def ingest_audio(self, pcm: bytes) -> None:
        x = pcm_to_float(pcm)
        if len(x) == 0:
            return
        self._ring = np.concatenate([self._ring, x])[-RING_SECONDS * SAMPLE_RATE:]
        # Enquanto a legenda está na tela, a fala é do falante ativo: acumula amostras p/ clonagem.
        if self.subtitle_active and self.active_profile is not None:
            mask = self._vad.process(x)
            frames = [x[i * FRAME:(i + 1) * FRAME] for i in np.flatnonzero(mask)]
            if frames:
                self.active_profile.add_samples(np.concatenate(frames), self.cfg.max_sample_seconds)

    # ---------- eventos do app ----------
    async def on_subtitle(self, utterance_id: int, text: str) -> None:
        await self._cancel_current()
        text = " ".join(text.split())
        if not text:
            return
        self.current_id = utterance_id
        self.subtitle_active = True
        self._task = asyncio.create_task(self._run(utterance_id, text, time.perf_counter()))

    async def on_stop(self, utterance_id: int) -> None:
        if utterance_id not in (0, self.current_id):
            return                                   # parada de uma fala que já foi substituída
        await self._cancel_current()
        self.subtitle_active = False
        self.active_profile = None
        await self.send_json({"type": "stopped", "id": utterance_id})

    async def close(self) -> None:
        await self._cancel_current()
        for t in list(self._bg):
            t.cancel()
        self.registry.save()

    # ---------- internos ----------
    async def _cancel_current(self) -> None:
        t, self._task = self._task, None
        if t and not t.done():
            t.cancel()
            try:
                await t
            except asyncio.CancelledError:
                pass

    async def _resolve_speaker(self) -> Optional[Profile]:
        deadline = time.perf_counter() + MAX_WAIT_SECONDS
        while True:
            window = self._ring[-int(WINDOW_SECONDS * SAMPLE_RATE):]
            voiced = voiced_samples(window)
            if len(voiced) >= MIN_VOICED_SECONDS * SAMPLE_RATE or time.perf_counter() >= deadline:
                break
            await asyncio.sleep(0.05)
        profile = self.registry.identify(voiced) if len(voiced) else None
        if profile is None:
            return self.last_profile
        profile.add_samples(voiced, self.cfg.max_sample_seconds)
        return profile

    async def _run(self, uid: int, text: str, t0: float) -> None:
        try:
            profile = await self._resolve_speaker()
            if profile is not None:
                self.active_profile = profile
                self.last_profile = profile
                self._maybe_clone(profile)
                voice = profile.voice_id or self.tts.fallback_voice(profile.gender)
                speaker_id = profile.id
            else:
                voice = self.tts.fallback_voice("neutral")
                speaker_id = "?"
            await self.send_json({"type": "speaker", "id": uid, "speaker": speaker_id,
                                  "cloned": bool(profile and profile.voice_id)})
            await self._speak(uid, text, voice, t0, profile)
        except asyncio.CancelledError:
            raise
        except Exception as e:  # noqa: BLE001
            log.exception("Falha ao dublar #%s", uid)
            await self.send_json({"type": "error", "id": uid, "message": str(e)[:200]})

    async def _speak(self, uid: int, text: str, voice: str, t0: float, profile: Optional[Profile]) -> None:
        sent_any = False
        try:
            async for chunk in self.tts.stream(text, voice):
                if not sent_any:
                    sent_any = True
                    await self.send_json({"type": "audio_start", "id": uid, "rate": self.tts.sample_rate,
                                          "latency_ms": int((time.perf_counter() - t0) * 1000)})
                await self.send_bytes(frame_audio(uid, chunk))
        except TTSError:
            # Voz clonada apagada/recusada: volta para a de reserva uma vez, sem travar a fala.
            if profile is not None and profile.voice_id == voice and not sent_any:
                log.warning("Voz clonada %s falhou; usando reserva", voice)
                profile.voice_id = None
                profile.cloned_seconds = 0.0
                await self._speak(uid, text, self.tts.fallback_voice(profile.gender), t0, None)
                return
            raise
        await self.send_json({"type": "audio_end", "id": uid})

    def _maybe_clone(self, profile: Profile) -> None:
        cfg = self.cfg
        secs = profile.seconds
        need_first = profile.voice_id is None and secs >= cfg.min_clone_seconds
        need_redo = (profile.voice_id is not None and profile.cloned_seconds < cfg.reclone_seconds
                     and secs >= cfg.reclone_seconds)
        if profile.cloning or not (need_first or need_redo):
            return
        profile.cloning = True
        task = asyncio.create_task(self._clone(profile))
        self._bg.add(task)
        task.add_done_callback(self._bg.discard)

    async def _clone(self, profile: Profile) -> None:
        try:
            wav = to_wav_bytes(profile.concat_samples(self.cfg.max_sample_seconds))
            new_voice = await self.tts.clone(f"dub-{self.game}-{profile.id}", wav)
            old, profile.voice_id = profile.voice_id, new_voice
            profile.cloned_seconds = profile.seconds
            log.info("Voz clonada para %s (%.0fs de amostra)", profile.id, profile.cloned_seconds)
            if old:
                await self.tts.delete_voice(old)
            self.registry.save()
        except Exception:  # noqa: BLE001
            log.exception("Clonagem falhou para %s", profile.id)
        finally:
            profile.cloning = False
