// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.dub

import java.text.Normalizer

/** Comparação tolerante a erros de OCR (acentos, maiúsculas, pontuação, letras trocadas). */
object TextSimilarity
{
	private val diacritics = Regex("\\p{Mn}+")
	private val nonAlnum = Regex("[^\\p{L}\\p{N}]+")

	fun normalize(s: String): String
	{
		val decomposed = Normalizer.normalize(s, Normalizer.Form.NFD)
		return diacritics.replace(decomposed, "")
			.lowercase()
			.let { nonAlnum.replace(it, " ") }
			.trim()
	}

	/** 1.0 = igual; 0.0 = totalmente diferente. Usa distância de Levenshtein normalizada. */
	fun similarity(a: String, b: String): Float
	{
		if(a == b)
			return 1f
		if(a.isEmpty() || b.isEmpty())
			return 0f
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
		val dist = prev[b.length]
		return 1f - dist.toFloat() / maxOf(a.length, b.length)
	}
}
