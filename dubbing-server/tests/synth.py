"""Vozes sintéticas para testes (fonte harmônica + formantes)."""
import numpy as np

SR = 16000


def voice(f0: float, formants, seconds: float = 1.5, seed: int = 0, amp: float = 0.3) -> np.ndarray:
    rng = np.random.default_rng(seed)
    n = int(seconds * SR)
    t = np.arange(n) / SR
    # leve vibrato + variação natural de tom
    inst = f0 * (1 + 0.02 * np.sin(2 * np.pi * 5 * t) + 0.01 * rng.standard_normal())
    phase = 2 * np.pi * np.cumsum(inst) / SR
    x = np.zeros(n)
    for h in range(1, int(4000 / f0)):
        freq = h * f0
        gain = 1.0 / h
        for fc, bw in formants:
            gain *= 1.0
        g = sum(np.exp(-0.5 * ((freq - fc) / bw) ** 2) for fc, bw in formants) + 0.05
        x += gain * g * np.sin(h * phase)
    x = x / (np.max(np.abs(x)) + 1e-9) * amp
    env = np.minimum(1.0, np.minimum(t, seconds - t) / 0.05)   # ataque/release
    return (x * env + 0.002 * rng.standard_normal(n)).astype(np.float32)


def voice_a(seed=0, seconds=1.5):   # grave, vogal "a"
    return voice(110, [(700, 120), (1200, 150)], seconds, seed)


def voice_b(seed=0, seconds=1.5):   # agudo, vogal "i"
    return voice(230, [(320, 80), (2300, 200)], seconds, seed)


def silence(seconds: float) -> np.ndarray:
    return (0.0008 * np.random.default_rng(1).standard_normal(int(seconds * SR))).astype(np.float32)


def to_pcm(x: np.ndarray) -> bytes:
    return (np.clip(x, -1, 1) * 32767).astype("<i2").tobytes()
