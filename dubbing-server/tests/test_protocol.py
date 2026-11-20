"""Testes do protocolo WebSocket com TTS falso (sem rede, sem custo)."""
import json

import numpy as np
import pytest
from fastapi.testclient import TestClient

from dubserver.app import create_app
from dubserver.config import Settings
from dubserver.speakers import SpectralEmbedder
from dubserver.tts import FakeTTS

from synth import silence, to_pcm, voice_a, voice_b

CHUNK = 1600  # 100 ms, como o app envia


def make_client(tmp_path, token="", **tts_kw):
    cfg = Settings(token=token, tts_backend="fake", data_dir=tmp_path, min_clone_seconds=3.0)
    tts = FakeTTS(**tts_kw)
    app = create_app(cfg, tts=tts, embedder=SpectralEmbedder())
    return TestClient(app), tts


def feed(ws, x):
    pcm = to_pcm(x)
    for i in range(0, len(pcm), CHUNK * 2):
        ws.send_bytes(pcm[i:i + CHUNK * 2])


def recv_until(ws, kinds, limit=400):
    """Lê mensagens até uma das mensagens JSON com type em kinds. Devolve (json, binários antes)."""
    binaries = []
    for _ in range(limit):
        m = ws.receive()
        if m.get("bytes") is not None:
            binaries.append(m["bytes"])
            continue
        data = json.loads(m["text"])
        if data["type"] in kinds:
            return data, binaries
    raise AssertionError(f"não recebi {kinds}")


def parse(frame):
    assert frame[0] == 1
    return int.from_bytes(frame[1:5], "big"), frame[5:]


def test_health(tmp_path):
    c, _ = make_client(tmp_path)
    with c:
        r = c.get("/health")
        assert r.json()["ok"] is True


def test_token_required(tmp_path):
    c, _ = make_client(tmp_path, token="segredo")
    with c:
        with pytest.raises(Exception):
            with c.websocket_connect("/ws?token=errado") as ws:
                ws.receive()
        with c.websocket_connect("/ws?token=segredo&game=g") as ws:
            assert json.loads(ws.receive()["text"])["type"] == "ready"


def test_subtitle_produces_streamed_audio_with_ids(tmp_path):
    c, _ = make_client(tmp_path)
    with c, c.websocket_connect("/ws?game=teste") as ws:
        ready, _ = recv_until(ws, {"ready"})
        assert ready["rate"] == 24000
        feed(ws, np.concatenate([silence(0.3), voice_a(seconds=1.5)]))
        ws.send_text(json.dumps({"type": "subtitle", "id": 7, "text": "Olá, mundo corajoso"}))
        spk, _ = recv_until(ws, {"speaker"})
        assert spk["id"] == 7 and spk["speaker"] == "S1"
        start, _ = recv_until(ws, {"audio_start"})
        assert start["id"] == 7 and start["rate"] == 24000
        end, chunks = recv_until(ws, {"audio_end"})
        assert end["id"] == 7 and len(chunks) > 3
        ids = {parse(f)[0] for f in chunks}
        assert ids == {7}
        samples = sum(len(parse(f)[1]) for f in chunks) // 2
        assert samples >= 24000 * 0.5          # 3 palavras ≈ 0,84 s de bipe


def test_two_voices_get_two_speakers_and_different_tts_voices(tmp_path):
    c, tts = make_client(tmp_path)
    with c, c.websocket_connect("/ws?game=duo") as ws:
        recv_until(ws, {"ready"})
        feed(ws, voice_a(seconds=1.6))
        ws.send_text(json.dumps({"type": "subtitle", "id": 1, "text": "um"}))
        s1, _ = recv_until(ws, {"speaker"})
        recv_until(ws, {"audio_end"})
        ws.send_text(json.dumps({"type": "stop", "id": 1}))
        recv_until(ws, {"stopped"})
        feed(ws, voice_b(seconds=1.6))
        ws.send_text(json.dumps({"type": "subtitle", "id": 2, "text": "dois"}))
        s2, _ = recv_until(ws, {"speaker"})
        recv_until(ws, {"audio_end"})
        assert s1["speaker"] != s2["speaker"]


def test_stop_cancels_stream_and_acks(tmp_path):
    c, _ = make_client(tmp_path, ms_per_word=400)
    with c, c.websocket_connect("/ws?game=stop") as ws:
        recv_until(ws, {"ready"})
        feed(ws, voice_a(seconds=1.6))
        long_text = " ".join(["palavra"] * 40)          # ~16 s de áudio falso
        ws.send_text(json.dumps({"type": "subtitle", "id": 3, "text": long_text}))
        recv_until(ws, {"audio_start"})
        ws.send_text(json.dumps({"type": "stop", "id": 3}))
        stopped, _ = recv_until(ws, {"stopped"})
        assert stopped["id"] == 3
        # Nada mais do id 3 pode chegar depois do ack: o próximo evento é o da fala nova.
        ws.send_text(json.dumps({"type": "subtitle", "id": 4, "text": "oi"}))
        end, chunks = recv_until(ws, {"audio_end"})
        assert end["id"] == 4
        assert {parse(f)[0] for f in chunks} == {4}


def test_new_subtitle_replaces_current(tmp_path):
    c, _ = make_client(tmp_path, ms_per_word=400)
    with c, c.websocket_connect("/ws?game=repl") as ws:
        recv_until(ws, {"ready"})
        feed(ws, voice_a(seconds=1.6))
        ws.send_text(json.dumps({"type": "subtitle", "id": 1, "text": " ".join(["longa"] * 30)}))
        recv_until(ws, {"audio_start"})
        ws.send_text(json.dumps({"type": "subtitle", "id": 2, "text": "curta"}))
        end, _ = recv_until(ws, {"audio_end"})
        # o primeiro audio_end que aparece tem que ser o da fala 2 (a 1 foi cancelada)
        assert end["id"] == 2


def test_voice_is_cloned_after_enough_speech_and_reused(tmp_path):
    c, tts = make_client(tmp_path)
    with c, c.websocket_connect("/ws?game=clone") as ws:
        recv_until(ws, {"ready"})
        for i in range(4):
            feed(ws, voice_a(seed=i, seconds=1.6))
            ws.send_text(json.dumps({"type": "subtitle", "id": i + 1, "text": "fala numero"}))
            recv_until(ws, {"audio_end"})
            ws.send_text(json.dumps({"type": "stop", "id": i + 1}))
            recv_until(ws, {"stopped"})
        feed(ws, voice_a(seed=9, seconds=1.6))
        ws.send_text(json.dumps({"type": "subtitle", "id": 99, "text": "agora clonada"}))
        spk, _ = recv_until(ws, {"speaker"})
        recv_until(ws, {"audio_end"})
    assert len(tts.cloned) >= 1
    assert spk["cloned"] is True


def test_unknown_speaker_when_no_audio_still_speaks(tmp_path):
    c, _ = make_client(tmp_path)
    with c, c.websocket_connect("/ws?game=mudo") as ws:
        recv_until(ws, {"ready"})
        ws.send_text(json.dumps({"type": "subtitle", "id": 1, "text": "sem audio"}))
        spk, _ = recv_until(ws, {"speaker"})
        assert spk["speaker"] == "?"
        end, chunks = recv_until(ws, {"audio_end"})
        assert chunks
