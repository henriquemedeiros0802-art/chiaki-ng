// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.dub

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/** Cliente WebSocket do servidor de dublagem, com reconexão automática. */
class DubClient(
	baseUrl: String,
	token: String,
	game: String,
	private val listener: Listener
)
{
	interface Listener
	{
		fun onConnectionChanged(connected: Boolean)
		fun onMessage(message: DubMessage)
	}

	companion object
	{
		private const val MAX_QUEUED_BYTES = 256_000L     // acima disso, descarta áudio em vez de acumular atraso
		private const val RETRY_MS = 2000L
	}

	private val url = DubUrl.build(baseUrl, token, game)
	private val http = OkHttpClient.Builder()
		.pingInterval(15, TimeUnit.SECONDS)
		.readTimeout(0, TimeUnit.MILLISECONDS)
		.build()
	private val handler = Handler(Looper.getMainLooper())

	@Volatile private var ws: WebSocket? = null
	@Volatile private var running = false
	@Volatile var connected = false
		private set

	fun connect()
	{
		running = true
		open()
	}

	private fun open()
	{
		if(!running)
			return
		val request = try { Request.Builder().url(url).build() }
		catch(e: IllegalArgumentException) { listener.onMessage(DubMessage.Error(0, "URL do servidor inválida")); return }

		ws = http.newWebSocket(request, object: WebSocketListener()
		{
			override fun onOpen(webSocket: WebSocket, response: Response)
			{
				connected = true
				listener.onConnectionChanged(true)
			}

			override fun onMessage(webSocket: WebSocket, text: String)
			{
				DubMessage.parseText(text)?.let { listener.onMessage(it) }
			}

			override fun onMessage(webSocket: WebSocket, bytes: ByteString)
			{
				DubMessage.parseBinary(bytes.toByteArray())?.let { listener.onMessage(it) }
			}

			override fun onClosing(webSocket: WebSocket, code: Int, reason: String)
			{
				webSocket.close(1000, null)
			}

			override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, code == 4401)

			override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket, response?.code == 401)
		})
	}

	private fun lost(socket: WebSocket, authFailed: Boolean)
	{
		if(socket !== ws)
			return
		connected = false
		listener.onConnectionChanged(false)
		if(authFailed)
			listener.onMessage(DubMessage.Error(0, "Token recusado pelo servidor"))
		if(running)
			handler.postDelayed({ open() }, RETRY_MS)
	}

	fun sendAudio(pcm16le: ByteArray)
	{
		val s = ws ?: return
		if(connected && s.queueSize() < MAX_QUEUED_BYTES)
			s.send(pcm16le.toByteString())
	}

	fun sendSubtitle(id: Int, text: String) { ws?.send(DubMessage.subtitle(id, text)) }
	fun sendStop(id: Int) { ws?.send(DubMessage.stop(id)) }

	fun close()
	{
		running = false
		handler.removeCallbacksAndMessages(null)
		ws?.close(1000, null)
		ws = null
		connected = false
		http.dispatcher.executorService.shutdown()
	}
}
