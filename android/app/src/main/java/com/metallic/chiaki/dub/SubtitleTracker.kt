// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.dub

/**
 * Máquina de estados que transforma leituras de OCR (uma por quadro) em eventos de legenda:
 * [Event.Start] quando uma legenda nova aparece e [Event.Stop] quando ela some ou é trocada.
 *
 * - Uma legenda só é confirmada depois de [confirmFrames] leituras seguidas parecidas
 *   (evita falar texto pela metade quando o jogo digita a legenda letra por letra).
 * - Ela só "some" depois de [clearFrames] leituras seguidas vazias (evita cortar por 1 quadro ruim).
 * - Pequenas variações do OCR na mesma legenda não reiniciam a fala.
 * - Se uma das linhas da legenda sumir (o texto novo está contido no ativo), a fala continua.
 */
class SubtitleTracker(
	private val confirmFrames: Int = 2,
	private val clearFrames: Int = 2,
	private val sameThreshold: Float = 0.8f,
	private val minChars: Int = 3
)
{
	sealed class Event
	{
		data class Start(val id: Int, val text: String): Event()
		data class Stop(val id: Int): Event()
	}

	private var nextId = 1
	private var activeId = 0
	private var activeNorm = ""
	private var candidateNorm = ""
	private var candidateRaw = ""
	private var candidateCount = 0
	private var emptyCount = 0

	val activeSubtitleId: Int get() = activeId

	fun onFrame(rawText: String?): List<Event>
	{
		val raw = rawText?.trim().orEmpty()
		val norm = TextSimilarity.normalize(raw)
		val events = mutableListOf<Event>()

		if(norm.length < minChars)
		{
			candidateCount = 0
			candidateNorm = ""
			emptyCount++
			if(activeId != 0 && emptyCount >= clearFrames)
			{
				events.add(Event.Stop(activeId))
				activeId = 0
				activeNorm = ""
			}
			return events
		}
		emptyCount = 0

		if(activeId != 0)
		{
			// mesma legenda com ruído de OCR, ou só uma das linhas sobrou: nada a fazer
			if(TextSimilarity.similarity(norm, activeNorm) >= sameThreshold || activeNorm.contains(norm))
			{
				candidateCount = 0
				candidateNorm = ""
				return events
			}
		}

		if(candidateCount > 0 && TextSimilarity.similarity(norm, candidateNorm) >= sameThreshold)
			candidateCount++
		else
			candidateCount = 1
		candidateNorm = norm
		candidateRaw = raw

		if(candidateCount >= confirmFrames)
		{
			if(activeId != 0)
				events.add(Event.Stop(activeId))
			activeId = nextId++
			activeNorm = candidateNorm
			events.add(Event.Start(activeId, candidateRaw))
			candidateCount = 0
			candidateNorm = ""
		}
		return events
	}

	fun reset(): List<Event>
	{
		val events = if(activeId != 0) listOf<Event>(Event.Stop(activeId)) else emptyList()
		activeId = 0
		activeNorm = ""
		candidateCount = 0
		candidateNorm = ""
		emptyCount = 0
		return events
	}
}
