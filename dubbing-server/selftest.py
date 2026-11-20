"""Teste real contra o ElevenLabs: mede o tempo até o primeiro áudio e salva um WAV.

Uso: ELEVENLABS_API_KEY=... python selftest.py "Olá, tudo bem?"
"""
import asyncio, sys, time, wave
from dubserver.config import Settings
from dubserver.tts import ElevenLabsTTS


async def main(text: str):
    cfg = Settings()
    tts = ElevenLabsTTS(cfg)
    await tts.warmup()
    voice = tts.fallback_voice("male")
    t0 = time.perf_counter()
    pcm, first = b"", None
    async for chunk in tts.stream(text, voice):
        if first is None:
            first = time.perf_counter() - t0
        pcm += chunk
    total = time.perf_counter() - t0
    with wave.open("selftest.wav", "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(tts.sample_rate); w.writeframes(pcm)
    print(f"formato={cfg.output_format} modelo={cfg.model_id}")
    print(f"primeiro áudio em {first*1000:.0f} ms; total {total*1000:.0f} ms; {len(pcm)/2/tts.sample_rate:.2f}s de áudio")
    print("salvo em selftest.wav")
    await tts.close()


asyncio.run(main(" ".join(sys.argv[1:]) or "Olá! Esta é uma dublagem de teste em português."))
