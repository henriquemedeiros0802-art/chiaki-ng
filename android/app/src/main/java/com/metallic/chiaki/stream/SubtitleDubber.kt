// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Reads the subtitle band of the PS5 stream (frames come from our own SurfaceView,
 * so FLAG_SECURE of the official app does not apply), translates it (EN -> PT) on-device
 * and speaks it with the Android text-to-speech voice.
 */
class SubtitleDubber(
	context: Context,
	private val surfaceView: SurfaceView,
	private val subtitleText: TextView,
	private val scope: CoroutineScope,
	private val onStatus: (String) -> Unit
)
{
	companion object
	{
		private const val INTERVAL_MS = 400L
		private const val SAME_TEXT_REQUIRED_READS = 2
		private const val MAX_PENDING_LINES = 2
		private const val SIMILARITY = 0.8

		/** (top, bottom) as fractions of the video height */
		val BANDS = listOf(
			Triple("Baixo", 0.78f, 0.995f),
			Triple("Meio-baixo", 0.58f, 0.85f),
			Triple("Topo", 0.03f, 0.28f)
		)
		/** subtitles are centred: ignore the minimap / HUD at the sides */
		private const val CROP_LEFT = 0.18f
		private const val CROP_RIGHT = 0.82f
	}

	private val mainHandler = Handler(Looper.getMainLooper())
	private val copyThread = HandlerThread("dub-pixelcopy").also { it.start() }
	private val copyHandler = Handler(copyThread.looper)

	private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	private val langId = LanguageIdentification.getClient()
	private val translators = HashMap<String, Translator>()

	private var ttsReady = false
	private var speaking = false
	private val pending = ArrayDeque<String>()
	private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
		if(status == TextToSpeech.SUCCESS)
		{
			setupTts()
		}
	}

	private var job: Job? = null
	var bandIndex = 0
		private set

	val bandName get() = BANDS[bandIndex].first

	private fun setupTts()
	{
		val result = tts.setLanguage(Locale("pt", "BR"))
		if(result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED)
			tts.setLanguage(Locale("pt"))
		tts.setSpeechRate(1.15f)
		tts.setOnUtteranceProgressListener(object: UtteranceProgressListener()
		{
			override fun onStart(utteranceId: String?) {}
			override fun onDone(utteranceId: String?) { mainHandler.post { speakNext() } }
			@Deprecated("Deprecated in Java")
			override fun onError(utteranceId: String?) { mainHandler.post { speakNext() } }
		})
		ttsReady = true
	}

	fun nextBand()
	{
		bandIndex = (bandIndex + 1) % BANDS.size
	}

	fun start()
	{
		if(job != null)
			return
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
		{
			onStatus("Precisa do Android 8 ou mais novo.")
			return
		}
		onStatus("Dublagem ligada")
		job = scope.launch {
			// warm up the English model in the background; Portuguese subtitles need no model
			launch { try { translatorFor(TranslateLanguage.ENGLISH).downloadModelIfNeeded(DownloadConditions.Builder().build()).await() } catch(e: Exception) {} }
			runLoop()
		}
	}

	private fun translatorFor(source: String): Translator =
		translators.getOrPut(source) {
			Translation.getClient(
				TranslatorOptions.Builder()
					.setSourceLanguage(source)
					.setTargetLanguage(TranslateLanguage.PORTUGUESE)
					.build()
			)
		}

	/** Portuguese (or unknown) text is spoken as is; other languages are translated on-device. */
	private suspend fun toPortuguese(text: String): String
	{
		val tag = try { langId.identifyLanguage(text).await() } catch(e: Exception) { "und" }
		if(tag == "und" || tag == "pt")
			return text
		val source = TranslateLanguage.fromLanguageTag(tag) ?: return text
		val tr = translatorFor(source)
		tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
		return tr.translate(text).await()
	}

	fun stop()
	{
		job?.cancel()
		job = null
		pending.clear()
		speaking = false
		if(ttsReady)
			tts.stop()
		subtitleText.visibility = View.GONE
	}

	fun release()
	{
		stop()
		tts.shutdown()
		recognizer.close()
		translators.values.forEach { it.close() }
		langId.close()
		copyThread.quitSafely()
	}

	private suspend fun runLoop()
	{
		var lastHash = 0L
		var lastText = ""
		var candidate = ""
		var candidateReads = 0
		var lastSpoken = ""

		while(currentCoroutineContext().isActive)
		{
			val bmp = grabBand()
			if(bmp != null)
			{
				try
				{
					val hash = quickHash(bmp)
					if(hash != lastHash)
					{
						lastHash = hash
						lastText = recognize(bmp)
					}
				}
				catch(e: Exception)
				{
					lastText = ""
				}
				finally
				{
					bmp.recycle()
				}

				if(!looksLikeSubtitle(lastText))
				{
					candidate = ""
					candidateReads = 0
				}
				else
				{
					if(similar(lastText, candidate))
						candidateReads++
					else
					{
						candidate = lastText
						candidateReads = 1
					}

					if(candidateReads == SAME_TEXT_REQUIRED_READS && !similar(candidate, lastSpoken))
					{
						lastSpoken = candidate
						try
						{
							val translated = toPortuguese(candidate)
							show(translated)
							enqueue(translated)
						}
						catch(e: Exception)
						{
							// ignore this line, try the next one
						}
					}
				}
			}
			delay(INTERVAL_MS)
		}
	}

	private suspend fun recognize(bmp: Bitmap): String
	{
		val result = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
		return result.text.replace(Regex("\\s+"), " ").trim()
	}

	private suspend fun grabBand(): Bitmap?
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
			return null
		return grabBandApi26()
	}

	@RequiresApi(Build.VERSION_CODES.O)
	private suspend fun grabBandApi26(): Bitmap?
	{
		val surface = surfaceView.holder.surface
		if(!surface.isValid)
			return null
		val frame = surfaceView.holder.surfaceFrame
		val w = frame.width()
		val h = frame.height()
		if(w <= 0 || h <= 0)
			return null
		val band = BANDS[bandIndex]
		val top = (h * band.second).toInt()
		val bottom = max(top + 1, (h * band.third).toInt())
		val rect = Rect((w * CROP_LEFT).toInt(), top, (w * CROP_RIGHT).toInt(), bottom)
		val bmp = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
		return suspendCancellableCoroutine { cont ->
			try
			{
				PixelCopy.request(surfaceView, rect, bmp, { result ->
					if(cont.isActive)
						cont.resume(if(result == PixelCopy.SUCCESS) bmp else null)
				}, copyHandler)
			}
			catch(e: Exception)
			{
				if(cont.isActive)
					cont.resume(null)
			}
		}
	}

	private fun quickHash(bmp: Bitmap): Long
	{
		val small = Bitmap.createScaledBitmap(bmp, 48, 12, false)
		val px = IntArray(48 * 12)
		small.getPixels(px, 0, 48, 0, 0, 48, 12)
		small.recycle()
		var h = 1125899906842597L
		for(p in px)
		{
			val lum = (((p shr 16) and 0xff) + ((p shr 8) and 0xff) + (p and 0xff)) / 3
			h = 31 * h + (lum shr 4)
		}
		return h
	}

	private fun looksLikeSubtitle(text: String): Boolean
	{
		if(text.length < 4)
			return false
		val letters = text.count { it.isLetter() }
		if(letters < 4 || letters.toDouble() / text.length < 0.6)
			return false
		return text.split(" ").count { w -> w.count { it.isLetter() } >= 2 } >= 2
	}

	private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }.take(200)

	private fun similar(a: String, b: String): Boolean
	{
		val x = normalize(a)
		val y = normalize(b)
		if(x.isEmpty() || y.isEmpty())
			return false
		if(x == y)
			return true
		val dist = levenshtein(x, y)
		return 1.0 - dist.toDouble() / max(x.length, y.length) >= SIMILARITY
	}

	private fun levenshtein(a: String, b: String): Int
	{
		var prev = IntArray(b.length + 1) { it }
		var cur = IntArray(b.length + 1)
		for(i in 1..a.length)
		{
			cur[0] = i
			for(j in 1..b.length)
			{
				val cost = if(a[i - 1] == b[j - 1]) 0 else 1
				cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
			}
			val t = prev; prev = cur; cur = t
		}
		return prev[b.length]
	}

	private fun show(text: String)
	{
		subtitleText.text = text
		subtitleText.visibility = View.VISIBLE
	}

	private fun enqueue(text: String)
	{
		pending.addLast(text)
		// keep the voice close to the game: drop old lines if we are falling behind
		while(pending.size > MAX_PENDING_LINES)
			pending.removeFirst()
		if(!speaking)
			speakNext()
	}

	private fun speakNext()
	{
		val next = pending.removeFirstOrNull()
		if(next == null || !ttsReady)
		{
			speaking = false
			return
		}
		speaking = true
		tts.speak(next, TextToSpeech.QUEUE_FLUSH, null, "dub")
	}
}
