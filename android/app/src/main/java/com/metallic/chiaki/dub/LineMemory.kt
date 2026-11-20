// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.dub

/**
 * Lembra as linhas de texto que já foram faladas, para que um texto fixo na tela
 * (por exemplo o objetivo da missão) seja lido só uma vez.
 *
 * [select] recebe as linhas lidas num quadro e devolve só o que deve ser falado:
 *  - linhas novas, se houver;
 *  - senão, as linhas da fala que já está tocando (para a fala não ser cortada);
 *  - senão, nada (tudo é texto antigo).
 */
class LineMemory(
	private val retentionMs: Long = 60_000L,
	private val sameThreshold: Float = 0.8f
)
{
	private class Entry(val norm: String, val ownerId: Int, var lastSeen: Long)

	private val entries = mutableListOf<Entry>()

	class Selection(val lines: List<String>, val hasNew: Boolean)

	fun select(lines: List<String>, activeId: Int, now: Long): Selection
	{
		entries.removeAll { now - it.lastSeen > retentionMs }
		val fresh = mutableListOf<String>()
		val current = mutableListOf<String>()
		for(line in lines)
		{
			val norm = TextSimilarity.normalize(line)
			if(norm.length < 2)
				continue
			val e = entries.firstOrNull { TextSimilarity.similarity(norm, it.norm) >= sameThreshold }
			if(e == null)
				fresh.add(line)
			else
			{
				e.lastSeen = now
				if(activeId != 0 && e.ownerId == activeId)
					current.add(line)
			}
		}
		return when
		{
			fresh.isNotEmpty() -> Selection(fresh, true)
			else -> Selection(current, false)
		}
	}

	/** Marca as linhas como já faladas pela legenda [id]. */
	fun markSpoken(lines: List<String>, id: Int, now: Long)
	{
		for(line in lines)
		{
			val norm = TextSimilarity.normalize(line)
			if(norm.length < 2)
				continue
			val e = entries.firstOrNull { TextSimilarity.similarity(norm, it.norm) >= sameThreshold }
			if(e == null)
				entries.add(Entry(norm, id, now))
			else
				e.lastSeen = now
		}
	}

	fun clear() = entries.clear()
}
