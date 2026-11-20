"""Utilidades de áudio: PCM, detecção de voz (VAD) e estimativa de tom (f0)."""
from __future__ import annotations

import io
import wave

import numpy as np

SAMPLE_RATE = 16000          # taxa do áudio que o app envia
FRAME = 320                  # 20 ms


def pcm_to_float(pcm: bytes) -> np.ndarray:
    if len(pcm) % 2:
        pcm = pcm[:-1]
    return np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0


def float_to_pcm(x: np.ndarray) -> bytes:
    return (np.clip(x, -1.0, 1.0) * 32767.0).astype("<i2").tobytes()


def to_wav_bytes(samples: np.ndarray, rate: int = SAMPLE_RATE) -> bytes:
    """samples: float32 [-1, 1]."""
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(float_to_pcm(samples))
    return buf.getvalue()


class EnergyVAD:
    """VAD por energia com piso de ruído adaptativo e 'hangover'.

    Simples de propósito: roda em CPU de qualquer servidor. Música alta de fundo
    pode marcar tudo como voz; nesse caso a identificação de falante piora.
    """

    def __init__(self, abs_floor: float = 0.01, ratio: float = 3.0, hangover: int = 4):
        self.abs_floor = abs_floor
        self.ratio = ratio
        self.hangover = hangover
        self.noise = 0.004
        self._hang = 0

    def process(self, x: np.ndarray) -> np.ndarray:
        """Devolve máscara booleana por quadro de 20 ms (descarta resto incompleto)."""
        n = len(x) // FRAME
        out = np.zeros(n, dtype=bool)
        for i in range(n):
            fr = x[i * FRAME:(i + 1) * FRAME]
            rms = float(np.sqrt(np.mean(fr * fr)) + 1e-9)
            if rms < self.noise:
                self.noise = 0.5 * self.noise + 0.5 * rms
            else:
                self.noise = min(self.noise * 1.003 + 1e-6, 0.05)
            voiced = rms > max(self.abs_floor, self.noise * self.ratio)
            if voiced:
                self._hang = self.hangover
            elif self._hang > 0:
                self._hang -= 1
                voiced = True
            out[i] = voiced
        return out


def voiced_samples(x: np.ndarray) -> np.ndarray:
    """Concatena só os quadros com voz (VAD novo, sem estado)."""
    mask = EnergyVAD().process(x)
    if not mask.any():
        return x[:0]
    frames = [x[i * FRAME:(i + 1) * FRAME] for i in np.flatnonzero(mask)]
    return np.concatenate(frames)


def estimate_f0(x: np.ndarray, rate: int = SAMPLE_RATE, fmin: float = 70.0, fmax: float = 400.0) -> float:
    """Mediana do f0 por autocorrelação em janelas de 40 ms. 0.0 se não achar."""
    win = int(0.04 * rate)
    lo, hi = int(rate / fmax), int(rate / fmin)
    estimates = []
    for start in range(0, len(x) - win - hi, win // 2):
        seg = x[start:start + win + hi]
        seg = seg - seg.mean()
        if np.sqrt(np.mean(seg * seg)) < 0.01:
            continue
        a = seg[:win]
        corr = np.array([np.dot(a, seg[lag:lag + win]) for lag in range(lo, hi)])
        energy = np.dot(a, a) + 1e-9
        k = int(np.argmax(corr))
        if corr[k] / energy > 0.35:
            estimates.append(rate / (lo + k))
    return float(np.median(estimates)) if estimates else 0.0


def gender_from_f0(f0: float) -> str:
    if f0 <= 0:
        return "neutral"
    if f0 < 160:
        return "male"
    if f0 < 300:
        return "female"
    return "neutral"
