// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.dub

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Modo local (grátis, sem servidor): fala a legenda com a voz em português que já vem no Android.
 * Uma única voz para todos os personagens.
 */
class LocalSpeaker(
	context: Context,
	private val onProblem: (String) -> Unit,
	private val onFinished: (Int) -> Unit,
	private val onReady: () -> Unit = {}
)
{
	private var tts: TextToSpeech? = null
	@Volatile private var ready = false

	init
	{
		tts = TextToSpeech(context.applicationContext) { status ->
			val engine = tts
			if(status != TextToSpeech.SUCCESS || engine == null)
			{
				onProblem("Voz do Android indisponível neste aparelho")
			}
			else
			{
				val r = engine.setLanguage(Locale("pt", "BR"))
				if(r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
					onProblem("Instale a voz em português: Ajustes do Android → Idioma → Saída de texto para fala")
				else
				{
					ready = true
					onReady()
				}
				engine.setOnUtteranceProgressListener(object: UtteranceProgressListener()
				{
					override fun onStart(utteranceId: String?) {}
					override fun onDone(utteranceId: String?) { utteranceId?.toIntOrNull()?.let(onFinished) }
					@Deprecated("Deprecated in Java")
					override fun onError(utteranceId: String?) { utteranceId?.toIntOrNull()?.let(onFinished) }
				})
			}
		}
	}

	/** Começa a falar já, interrompendo qualquer fala anterior. */
	fun speak(id: Int, text: String)
	{
		if(ready)
			tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id.toString())
	}

	/** Corta a fala na hora (legenda sumiu). */
	fun stop() { tts?.stop() }

	fun shutdown()
	{
		tts?.stop()
		tts?.shutdown()
		tts = null
		ready = false
	}
}
