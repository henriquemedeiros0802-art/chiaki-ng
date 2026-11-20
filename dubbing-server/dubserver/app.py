"""Aplicação FastAPI: /health e /ws."""
from __future__ import annotations

import asyncio
import hmac
import json
import logging
import re
from contextlib import asynccontextmanager
from typing import Optional

from fastapi import FastAPI, WebSocket, WebSocketDisconnect

from .config import Settings
from .session import DubSession
from .speakers import Embedder, Registry, make_embedder
from .tts import TTSBackend, make_tts

log = logging.getLogger("dub.app")
GAME_RE = re.compile(r"[^A-Za-z0-9_-]")


def create_app(settings: Optional[Settings] = None, tts: Optional[TTSBackend] = None,
               embedder: Optional[Embedder] = None) -> FastAPI:
    cfg = settings or Settings()
    registries: dict[str, Registry] = {}

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.cfg = cfg
        app.state.tts = tts or make_tts(cfg)
        app.state.embedder = embedder or make_embedder(cfg.embedder)
        await app.state.tts.warmup()
        yield
        for r in registries.values():
            r.save()
        await app.state.tts.close()

    app = FastAPI(title="Dublagem de legendas", lifespan=lifespan)

    def registry_for(game: str) -> Registry:
        if game not in registries:
            registries[game] = Registry(app.state.embedder, cfg.data_dir / game, cfg.match_threshold,
                                        cfg.max_speakers, cfg.max_sample_seconds)
        return registries[game]

    @app.get("/health")
    async def health():
        return {"ok": True, "tts": cfg.tts_backend, "model": cfg.model_id, "rate": cfg.output_rate}

    @app.websocket("/ws")
    async def ws_endpoint(ws: WebSocket):
        if cfg.token and not hmac.compare_digest(ws.query_params.get("token", ""), cfg.token):
            await ws.close(code=4401)
            return
        await ws.accept()
        lock = asyncio.Lock()

        async def send_json(obj: dict) -> None:
            async with lock:
                await ws.send_text(json.dumps(obj))

        async def send_bytes(data: bytes) -> None:
            async with lock:
                await ws.send_bytes(data)

        game = GAME_RE.sub("_", ws.query_params.get("game", "default"))[:40] or "default"
        session = DubSession(game, send_json, send_bytes, app.state.tts, registry_for(game), cfg)
        await send_json({"type": "ready", "rate": app.state.tts.sample_rate, "game": game})
        try:
            while True:
                msg = await ws.receive()
                if msg["type"] == "websocket.disconnect":
                    break
                if msg.get("bytes") is not None:
                    session.ingest_audio(msg["bytes"])
                elif msg.get("text") is not None:
                    try:
                        data = json.loads(msg["text"])
                    except json.JSONDecodeError:
                        continue
                    kind = data.get("type")
                    if kind == "subtitle":
                        await session.on_subtitle(int(data.get("id", 0)), str(data.get("text", "")))
                    elif kind == "stop":
                        await session.on_stop(int(data.get("id", 0)))
                    elif kind == "ping":
                        await send_json({"type": "pong", "t": data.get("t")})
        except WebSocketDisconnect:
            pass
        finally:
            await session.close()

    return app


def main() -> None:  # pragma: no cover
    import uvicorn
    logging.basicConfig(level=logging.INFO)
    uvicorn.run(create_app(), host="0.0.0.0", port=int(__import__("os").environ.get("PORT", "8765")))


if __name__ == "__main__":  # pragma: no cover
    main()
