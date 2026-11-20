// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.dub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineMemoryTest
{
	private val dialogue = "Jason: Foco, Lu, a gente consegue!"
	private val objective = "Dispute corrida até o iate."

	@Test
	fun objectiveIsSpokenOnlyOnce()
	{
		val m = LineMemory()
		val first = m.select(listOf(dialogue, objective), 0, 0)
		assertEquals(2, first.lines.size)
		m.markSpoken(first.lines, 1, 0)

		// o diálogo sumiu, o objetivo continua na tela: nada novo
		val second = m.select(listOf(objective), 0, 5000)
		assertTrue(second.lines.isEmpty())

		// outro diálogo com o objetivo ainda na tela: fala só o diálogo novo
		val third = m.select(listOf("Mattia: Vira pra esquerda!", objective), 0, 9000)
		assertEquals(listOf("Mattia: Vira pra esquerda!"), third.lines)
	}

	@Test
	fun activeLinesKeepSpeakingWithoutBeingNew()
	{
		val m = LineMemory()
		m.markSpoken(listOf(dialogue), 1, 0)
		val s = m.select(listOf(dialogue), 1, 1000)
		assertEquals(listOf(dialogue), s.lines)
		assertTrue(!s.hasNew)
	}

	@Test
	fun forgetsAfterRetention()
	{
		val m = LineMemory(retentionMs = 1000)
		m.markSpoken(listOf(objective), 1, 0)
		assertEquals(1, m.select(listOf(objective), 0, 5000).lines.size)
	}
}
