"""Identificação de falantes e acúmulo de amostras para clonagem de voz."""
from __future__ import annotations

import json
import logging
import wave
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional, Protocol

import numpy as np

from .audio import SAMPLE_RATE, estimate_f0, gender_from_f0

log = logging.getLogger("dub.speakers")


class Embedder(Protocol):
    default_threshold: float

    def embed(self, x: np.ndarray) -> Optional[np.ndarray]: ...
    def similarity(self, a: np.ndarray, b: np.ndarray) -> float: ...


class SpectralEmbedder:
    """Embedding leve (numpy puro): cepstro médio + tom. Só para fallback e testes.

    Funciona para separar vozes bem diferentes (grave/aguda, timbres distintos), mas é
    muito inferior a um modelo neural. Em produção use ResemblyzerEmbedder.
    """

    default_threshold = 0.55
    N_MELS = 24
    N_CEP = 12
    FFT = 512
    HOP = 160

    def __init__(self) -> None:
        self._mel = self._mel_filterbank()
        k = np.arange(self.N_MELS)
        self._dct = np.cos(np.pi / self.N_MELS * (k + 0.5)[None, :] * np.arange(1, self.N_CEP + 1)[:, None])

    def _mel_filterbank(self) -> np.ndarray:
        def hz2mel(f): return 2595 * np.log10(1 + f / 700)
        def mel2hz(m): return 700 * (10 ** (m / 2595) - 1)
        pts = mel2hz(np.linspace(hz2mel(80), hz2mel(SAMPLE_RATE / 2 - 100), self.N_MELS + 2))
        bins = np.floor((self.FFT + 1) * pts / SAMPLE_RATE).astype(int)
        fb = np.zeros((self.N_MELS, self.FFT // 2 + 1))
        for i in range(1, self.N_MELS + 1):
            l, c, r = bins[i - 1], bins[i], bins[i + 1]
            for j in range(l, c):
                fb[i - 1, j] = (j - l) / max(c - l, 1)
            for j in range(c, r):
                fb[i - 1, j] = (r - j) / max(r - c, 1)
        return fb

    def embed(self, x: np.ndarray) -> Optional[np.ndarray]:
        if len(x) < int(0.3 * SAMPLE_RATE):
            return None
        win = np.hanning(self.FFT)
        frames = []
        for s in range(0, len(x) - self.FFT, self.HOP):
            seg = x[s:s + self.FFT]
            if np.sqrt(np.mean(seg * seg)) < 0.008:
                continue
            spec = np.abs(np.fft.rfft(seg * win)) ** 2
            frames.append(np.log(self._mel @ spec + 1e-8))
        if len(frames) < 8:
            return None
        logmel = np.mean(frames, axis=0)
        cep = self._dct @ logmel / self.N_MELS
        f0 = estimate_f0(x)
        pitch = np.log2(f0 / 130.0) if f0 > 0 else 0.0
        return np.concatenate([cep, [pitch * 0.8]]).astype(np.float32)

    def similarity(self, a: np.ndarray, b: np.ndarray) -> float:
        return float(np.exp(-np.linalg.norm(a - b) / 1.2))


class ResemblyzerEmbedder:
    """Embedding neural (GE2E). Requer: pip install resemblyzer."""

    default_threshold = 0.78

    def __init__(self) -> None:
        from resemblyzer import VoiceEncoder  # import tardio: dependência pesada
        self._enc = VoiceEncoder(device="cpu")

    def embed(self, x: np.ndarray) -> Optional[np.ndarray]:
        if len(x) < int(0.4 * SAMPLE_RATE):
            return None
        return self._enc.embed_utterance(x.astype(np.float32))

    def similarity(self, a: np.ndarray, b: np.ndarray) -> float:
        return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-9))


def make_embedder(kind: str) -> Embedder:
    if kind in ("auto", "resemblyzer"):
        try:
            emb = ResemblyzerEmbedder()
            log.info("Embedder: resemblyzer")
            return emb
        except Exception as e:  # noqa: BLE001
            if kind == "resemblyzer":
                raise
            log.warning("Resemblyzer indisponível (%s); usando embedder espectral simples", e)
    return SpectralEmbedder()


@dataclass
class Profile:
    id: str
    centroid: np.ndarray
    n: int = 1
    f0: float = 0.0
    gender: str = "neutral"
    voice_id: Optional[str] = None          # voz clonada (None = usa voz de reserva)
    cloned_seconds: float = 0.0
    samples: list = field(default_factory=list)  # lista de float32 arrays (fala isolada)
    cloning: bool = False

    @property
    def seconds(self) -> float:
        return sum(len(s) for s in self.samples) / SAMPLE_RATE

    def add_samples(self, x: np.ndarray, max_seconds: float) -> None:
        if len(x) == 0:
            return
        self.samples.append(x.astype(np.float32))
        total = sum(len(s) for s in self.samples)
        cap = int(max_seconds * SAMPLE_RATE)
        while total > cap and len(self.samples) > 1:
            total -= len(self.samples.pop(0))

    def concat_samples(self, max_seconds: float) -> np.ndarray:
        if not self.samples:
            return np.zeros(0, dtype=np.float32)
        x = np.concatenate(self.samples)
        return x[-int(max_seconds * SAMPLE_RATE):]


class Registry:
    """Conjunto de falantes de um jogo; persiste em disco para as próximas sessões."""

    def __init__(self, embedder: Embedder, directory: Optional[Path], threshold: float = 0.0,
                 max_speakers: int = 12, max_sample_seconds: float = 60.0):
        self.embedder = embedder
        self.dir = directory
        self.threshold = threshold or embedder.default_threshold
        self.max_speakers = max_speakers
        self.max_sample_seconds = max_sample_seconds
        self.profiles: list[Profile] = []
        if directory:
            self._load()

    def identify(self, x: np.ndarray) -> Optional[Profile]:
        """x: fala isolada (float32, 16 kHz). None se for curto demais para decidir."""
        emb = self.embedder.embed(x)
        if emb is None:
            return None
        best, best_sim = None, -1.0
        for p in self.profiles:
            s = self.embedder.similarity(emb, p.centroid)
            if s > best_sim:
                best, best_sim = p, s
        if best is not None and (best_sim >= self.threshold or len(self.profiles) >= self.max_speakers):
            best.centroid = (best.centroid * best.n + emb) / (best.n + 1)
            best.n = min(best.n + 1, 50)
            return best
        f0 = estimate_f0(x)
        p = Profile(id=f"S{len(self.profiles) + 1}", centroid=emb, f0=f0, gender=gender_from_f0(f0))
        self.profiles.append(p)
        log.info("Novo falante %s (f0=%.0f Hz, %s)", p.id, f0, p.gender)
        return p

    # --- persistência ---
    def save(self) -> None:
        if not self.dir:
            return
        self.dir.mkdir(parents=True, exist_ok=True)
        data = []
        for p in self.profiles:
            data.append({"id": p.id, "centroid": p.centroid.tolist(), "n": p.n, "f0": p.f0,
                         "gender": p.gender, "voice_id": p.voice_id, "cloned_seconds": p.cloned_seconds})
            x = p.concat_samples(self.max_sample_seconds)
            if len(x):
                with wave.open(str(self.dir / f"{p.id}.wav"), "wb") as w:
                    w.setnchannels(1)
                    w.setsampwidth(2)
                    w.setframerate(SAMPLE_RATE)
                    w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
        (self.dir / "speakers.json").write_text(json.dumps(data))

    def _load(self) -> None:
        f = self.dir / "speakers.json"
        if not f.exists():
            return
        try:
            for d in json.loads(f.read_text()):
                p = Profile(id=d["id"], centroid=np.array(d["centroid"], dtype=np.float32), n=d["n"],
                            f0=d["f0"], gender=d["gender"], voice_id=d.get("voice_id"),
                            cloned_seconds=d.get("cloned_seconds", 0.0))
                wavf = self.dir / f"{p.id}.wav"
                if wavf.exists():
                    with wave.open(str(wavf), "rb") as w:
                        raw = w.readframes(w.getnframes())
                    p.samples = [np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0]
                self.profiles.append(p)
        except Exception:  # noqa: BLE001
            log.exception("Falha ao carregar %s; começando do zero", f)
            self.profiles = []
