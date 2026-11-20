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
import android.speech.tts.Voice
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.TextView
import androidx.annotation.RequiresApi
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
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Test version: reads the subtitle band of the PS5 stream (frames come from our own SurfaceView,
 * so the screenshot block of the official app does not apply) and reads it aloud with the
 * Android text-to-speech voice. The voice is female or male depending on the speaker.
 */
class SubtitleDubber(
	context: Context,
	private val surfaceView: SurfaceView,
	private val subtitleText: TextView,
	private val scope: CoroutineScope,
	private val onStatus: (String) -> Unit
)
{
	enum class Gender { FEMALE, MALE }

	/** AUTO guesses from a "Name:" prefix in the subtitle, otherwise keeps the last voice. */
	enum class VoiceMode(val label: String) { AUTO("Auto"), FEMALE("Feminina"), MALE("Masculina") }

	private class Line(val text: String, val gender: Gender)

	companion object
	{
		private const val INTERVAL_MS = 400L
		private const val SAME_TEXT_REQUIRED_READS = 2
		private const val MAX_PENDING_LINES = 2
		private const val SIMILARITY = 0.8

		/** (name, top, bottom) as fractions of the video height */
		val BANDS = listOf(
			Triple("Baixo", 0.78f, 0.995f),
			Triple("Meio-baixo", 0.58f, 0.85f),
			Triple("Topo", 0.03f, 0.28f)
		)
		/** subtitles are centred: ignore the minimap / HUD at the sides */
		private const val CROP_LEFT = 0.18f
		private const val CROP_RIGHT = 0.82f

		private val FEMALE_NAMES = setOf("lucia", "roxy", "maria", "ana", "julia", "juliana", "mae", "mulher", "garota", "menina")
		private val MALE_NAMES = setOf("jason", "cal", "boobie", "raul", "brian", "carlos", "joao", "pai", "homem", "garoto", "menino")
		private val NAME_PREFIX = Regex("^\\s*([\\p{L}'.]{2,20})\\s*[:\\-–]\\s*(.+)$")
	}

	private val mainHandler = Handler(Looper.getMainLooper())
	private val copyThread = HandlerThread("dub-pixelcopy").also { it.start() }
	private val copyHandler = Handler(copyThread.looper)

	private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

	private var ttsReady = false
	private var speaking = false
	private var femaleVoice: Voice? = null
	private var maleVoice: Voice? = null
	private val pending = ArrayDeque<Line>()
	private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
		if(status == TextToSpeech.SUCCESS)
			setupTts()
	}

	private var job: Job? = null
	var bandIndex = 0
		private set
	var voiceMode = VoiceMode.AUTO
		private set
	private var lastGender = Gender.MALE

	val bandName get() = BANDS[bandIndex].first
	val voiceModeName get() = voiceMode.label

	private fun setupTts()
	{
		var result = tts.setLanguage(Locale("pt", "BR"))
		if(result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED)
			result = tts.setLanguage(Locale("pt"))
		tts.setSpeechRate(1.1f)
		try
		{
			val voices = tts.voices?.filter { it.locale.language == "pt" } ?: emptyList()
			femaleVoice = voices.firstOrNull { Regex("afs|female|fem").containsMatchIn(it.name.lowercase()) }
			maleVoice = voices.firstOrNull { Regex("ptd|pte|ptm|(^|[^a-z])male").containsMatchIn(it.name.lowercase()) }
		}
		catch(e: Exception)
		{
			// keep default voice, the pitch change below still separates male and female
		}
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

	fun nextVoiceMode()
	{
		val all = VoiceMode.values()
		voiceMode = all[(voiceMode.ordinal + 1) % all.size]
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
		onStatus("Leitura da legenda ligada")
		job = scope.launch { runLoop() }
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
						val (spokenText, gender) = analyse(candidate)
						show(spokenText)
						enqueue(Line(spokenText, gender))
					}
				}
			}
			delay(INTERVAL_MS)
		}
	}

	/** Splits an optional "Name: text" prefix and decides the voice gender. */
	private fun analyse(text: String): Pair<String, Gender>
	{
		var body = text
		var gender: Gender? = null
		val m = NAME_PREFIX.find(text)
		if(m != null)
		{
			val name = stripAccents(m.groupValues[1]).lowercase().trim('.', '\'')
			if(name in FEMALE_NAMES) gender = Gender.FEMALE
			else if(name in MALE_NAMES) gender = Gender.MALE
			if(gender != null || m.groupValues[1].first().isUpperCase())
				body = m.groupValues[2]
		}
		val result = when(voiceMode)
		{
			VoiceMode.FEMALE -> Gender.FEMALE
			VoiceMode.MALE -> Gender.MALE
			VoiceMode.AUTO -> gender ?: lastGender
		}
		lastGender = result
		return Pair(body, result)
	}

	private fun stripAccents(s: String) =
		Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

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

	private fun enqueue(line: Line)
	{
		pending.addLast(line)
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
		val voice = if(next.gender == Gender.FEMALE) femaleVoice else maleVoice
		try { if(voice != null) tts.voice = voice } catch(e: Exception) {}
		// pitch separates the voices even when the phone has only one Portuguese voice
		tts.setPitch(if(next.gender == Gender.FEMALE) 1.25f else 0.85f)
		tts.speak(next.text, TextToSpeech.QUEUE_FLUSH, null, "dub")
	}
}
