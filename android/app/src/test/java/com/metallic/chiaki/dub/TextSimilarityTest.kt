// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.dub

import org.junit.Assert.*
import org.junit.Test

class TextSimilarityTest
{
	@Test fun normalizeStripsAccentsCasePunctuation()
	{
		assertEquals("ola mundo ainda nao", TextSimilarity.normalize("  Olá, MUNDO!  Ainda — não… "))
	}

	@Test fun identicalAndDifferent()
	{
		assertEquals(1f, TextSimilarity.similarity("abc", "abc"), 0f)
		assertEquals(0f, TextSimilarity.similarity("abc", ""), 0f)
		assertTrue(TextSimilarity.similarity("abcdef", "uvwxyz") < 0.2f)
	}

	@Test fun oneCharErrorIsStillSimilar()
	{
		assertTrue(TextSimilarity.similarity("eu nunca vou desistir", "eu nunca vou desistlr") > 0.9f)
	}
}
