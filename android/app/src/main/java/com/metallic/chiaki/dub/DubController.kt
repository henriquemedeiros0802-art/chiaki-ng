// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.dub

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import com.metallic.chiaki.lib.SessionAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Orquestra a dublagem enquanto o stream roda:
 *  - lê a legenda na tela (recorte + OCR) e detecta quando ela aparece e some;
 *  - envia ao servidor o áudio do jogo (para identificar quem fala) e a legenda;
 *  - recebe a voz em português por streaming, mistura por cima do jogo e abaixa o volume original;
 *  - corta a voz com fade-out quando a legenda some.
 */
@RequiresApi(Build.VERSION_CODES.O)
class DubController(
	private val context: Context,
	private val surfaceView: SurfaceView,
	private val audioProvider: () -> SessionAudio?,
	private val prefs: DubPreferences,
	private val onStatus: (String) -> Unit
)
{
	companion object
	{
		private const val OCR_MAX_WIDTH = 960
		private const val UNCHANGED_THRESHOLD = 3f          // diferença média de brilho (0..255) para reaproveitar o OCR
		private const val SEND_CHUNK_SAMPLES = 1600           // 100 ms a 16 kHz
	}

	private var scope: CoroutineScope? = null
	private var localSpeaker: LocalSpeaker? = null
	@Volatile private var localActiveId = 0
	private var client: DubClient? = null
	private val playout = DubPlayout()
	private var tracker = SubtitleTracker()
	private var duckGain = 0.2f

	@Volatile private var resampler: LinearResampler? = null
	@Volatile private var outRate = 48000
	@Volatile private var connected = false
	@Volatile private var speaker = ""
	@Volatile private var latencyMs = 0
	@Volatile private var lastText = ""
	@Volatile private var lastError = ""

	private val listener = object: DubClient.Listener
	{
		override fun onConnectionChanged(connected: Boolean)
		{
			this@DubController.connected = connected
			publishStatus()
		}

		override fun onMessage(message: DubMessage)
		{
			when(message)
			{
				is DubMessage.Speaker -> if(message.id == playout.activeId)
				{
					speaker = message.speaker + if(message.cloned) " (voz clonada)" else " (voz padrão)"
					publishStatus()
				}
				is DubMessage.AudioStart -> if(message.id == playout.activeId)
				{
					resampler = LinearResampler(message.rate, outRate)
					latencyMs = message.latencyMs
					publishStatus()
				}
				is DubMessage.Audio ->
				{
					val r = resampler
					if(r != null && message.id == playout.activeId)
						playout.enqueue(message.id, r.process(message.pcm))
				}
				is DubMessage.AudioEnd -> playout.markEnd(message.id)
				is DubMessage.Error ->
				{
					lastError = message.message
					publishStatus()
				}
				else -> {}
			}
		}
	}

	fun start()
	{
		if(scope != null)
			return
		if(prefs.mode == DubPreferences.MODE_LOCAL)
		{
			startLocal()
			return
		}
		if(prefs.serverUrl.isBlank())
		{
			onStatus("Dublagem: informe o endereço do servidor em Ajustes → Dublagem")
			return
		}
		duckGain = prefs.duckGain
		tracker = SubtitleTracker(confirmFrames = prefs.confirmFrames)
		val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		scope = s
		val c = DubClient(prefs.serverUrl, prefs.token, prefs.game, listener)
		client = c
		c.connect()
		s.launch { audioPump(c) }
		s.launch { visionLoop(c) }
		s.launch { pacer() }
		publishStatus()
	}

	/** Modo grátis: só lê a legenda na tela e fala com a voz do Android. */
	private fun startLocal()
	{
		duckGain = prefs.duckGain
		tracker = SubtitleTracker(confirmFrames = prefs.confirmFrames)
		val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		scope = s
		localSpeaker = LocalSpeaker(
			context,
			onProblem = { msg -> lastError = msg; publishStatus() },
			onFinished = { id ->
				if(id == localActiveId)
					audioProvider()?.setGains(1f, 1f)       // terminou de falar: devolve o volume do jogo
			})
		connected = true
		publishStatus()
		s.launch { visionLoop(null) }
	}

	fun stop()
	{
		scope?.cancel()
		scope = null
		localSpeaker?.shutdown()
		localSpeaker = null
		localActiveId = 0
		client?.close()
		client = null
		val audio = audioProvider()
		playout.cancel { audio?.dubFlush() }
		audio?.setGains(1f, 1f)
		audio?.setTap(false)
		tracker.reset()
		connected = false
		onStatus("")
	}

	// ---------------- áudio do jogo → servidor ----------------

	private suspend fun audioPump(c: DubClient)
	{
		val buf = ShortArray(4096)
		val chunk = ShortArray(SEND_CHUNK_SAMPLES)
		var filled = 0
		var down: MonoDownsampler? = null
		var formatKey = 0L
		while(scope?.isActive == true)
		{
			val audio = audioProvider()
			val (channels, rate) = audio?.format() ?: Pair(0, 0)
			if(audio == null || channels <= 0 || rate <= 0)
			{
				delay(150)
				continue
			}
			audio.setTap(true)
			val key = (channels.toLong() shl 32) or rate.toLong()
			if(key != formatKey || down == null)
			{
				down = MonoDownsampler(rate, channels)
				formatKey = key
				outRate = rate
			}
			val readMax = buf.size - buf.size % channels
			while(true)
			{
				val n = audio.tapRead(buf, readMax)
				if(n <= 0)
					break
				for(s in down.process(buf, n))
				{
					chunk[filled++] = s
					if(filled == chunk.size)
					{
						c.sendAudio(Pcm.toBytes(chunk))
						filled = 0
					}
				}
			}
			delay(25)
		}
	}

	// ---------------- tela → legenda ----------------

	private suspend fun visionLoop(c: DubClient?)
	{
		val grabber = FrameGrabber(surfaceView)
		val ocr = OcrEngine()
		val analyzer = RegionAnalyzer()
		var pixels = IntArray(0)
		var previousFingerprint: IntArray? = null
		var cachedText = ""
		try
		{
			while(scope?.isActive == true)
			{
				val t0 = SystemClock.elapsedRealtime()
				val bitmap = grabber.grab(prefs.region, OCR_MAX_WIDTH)
				if(bitmap != null)
				{
					val w = bitmap.width
					val h = bitmap.height
					if(pixels.size != w * h)
						pixels = IntArray(w * h)
					bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
					val analysis = analyzer.analyze(pixels, w, h)

					val text = when
					{
						// sem texto claro no recorte: a legenda sumiu, sem gastar OCR
						prefs.textMode == DubPreferences.TEXT_MODE_WHITE && !analysis.whiteTextLikely ->
						{
							previousFingerprint = null
							cachedText = ""
							""
						}
						// recorte praticamente igual ao anterior: reaproveita a leitura
						previousFingerprint != null && analyzer.difference(previousFingerprint, analysis.fingerprint) < UNCHANGED_THRESHOLD -> cachedText
						else ->
						{
							val read = ocr.recognize(bitmap)
							previousFingerprint = analysis.fingerprint
							cachedText = read
							read
						}
					}
					handle(tracker.onFrame(text), c)
				}
				val elapsed = SystemClock.elapsedRealtime() - t0
				delay(maxOf(10L, prefs.intervalMs - elapsed))
			}
		}
		finally
		{
			ocr.close()
			grabber.release()
		}
	}

	private fun handle(events: List<SubtitleTracker.Event>, c: DubClient?)
	{
		for(e in events)
		{
			when(e)
			{
				is SubtitleTracker.Event.Start ->
				{
					val audio = audioProvider()
					val local = localSpeaker
					if(c == null && local != null)
					{
						localActiveId = e.id
						lastText = e.text
						audio?.setGains(duckGain, 1f)
						local.speak(e.id, e.text)
						publishStatus()
						continue
					}
					playout.begin(e.id, outRate)
					resampler = null
					speaker = ""
					lastText = e.text
					audio?.setGains(duckGain, 1f)
					c?.sendSubtitle(e.id, e.text)
					publishStatus()
				}
				is SubtitleTracker.Event.Stop ->
				{
					if(c == null)
					{
						localSpeaker?.stop()                      // legenda sumiu: corta a voz na hora
						audioProvider()?.setGains(1f, 1f)
						localActiveId = 0
						lastText = ""
						publishStatus()
						continue
					}
					c.sendStop(e.id)
					val audio = audioProvider()
					playout.cancel { audio?.dubFlush() }      // fade-out de 80 ms e descarta o que sobrou
					audio?.setGains(1f, 1f)
					lastText = ""
					publishStatus()
				}
			}
		}
	}

	// ---------------- fila → saída de áudio ----------------

	private suspend fun pacer()
	{
		while(scope?.isActive == true)
		{
			val audio = audioProvider()
			if(audio != null)
			{
				playout.pump { a, off, n -> audio.dubWrite(a, off, n) }
				if(playout.isFinished())
				{
					audio.setGains(1f, 1f)                      // terminou de falar: devolve o volume do jogo
					playout.cancel { }
				}
			}
			delay(8)
		}
	}

	private fun publishStatus()
	{
		val local = prefs.mode == DubPreferences.MODE_LOCAL
		val sb = StringBuilder(if(local) "Dublagem (voz do Android) " else "Dublagem ")
		sb.append(if(connected) (if(local) "● ativa" else "● online") else "○ conectando…")
		if(speaker.isNotEmpty()) sb.append(" · ").append(speaker)
		if(latencyMs > 0) sb.append(" · ").append(latencyMs).append(" ms")
		if(lastText.isNotEmpty()) sb.append("\n").append(lastText.take(60))
		if(lastError.isNotEmpty()) sb.append("\n⚠ ").append(lastError.take(80))
		onStatus(sb.toString())
	}
}
