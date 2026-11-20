// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.dub

import com.metallic.chiaki.dub.SubtitleTracker.Event
import org.junit.Assert.*
import org.junit.Test

class SubtitleTrackerTest
{
	private fun feed(t: SubtitleTracker, vararg frames: String?): List<Event> = frames.flatMap { t.onFrame(it) }

	@Test fun startsAfterConfirmFrames()
	{
		val t = SubtitleTracker(confirmFrames = 2)
		assertTrue(t.onFrame("Olá, mundo!").isEmpty())
		val ev = t.onFrame("Olá, mundo!")
		assertEquals(listOf<Event>(Event.Start(1, "Olá, mundo!")), ev)
	}

	@Test fun confirmOneStartsImmediately()
	{
		val t = SubtitleTracker(confirmFrames = 1)
		assertEquals(listOf<Event>(Event.Start(1, "Vamos embora")), t.onFrame("Vamos embora"))
	}

	@Test fun stopsAfterClearFrames()
	{
		val t = SubtitleTracker(confirmFrames = 1, clearFrames = 2)
		t.onFrame("Vamos embora")
		assertTrue(t.onFrame("").isEmpty())
		assertEquals(listOf<Event>(Event.Stop(1)), t.onFrame(null))
		assertEquals(0, t.activeSubtitleId)
	}

	@Test fun singleEmptyFrameDoesNotStop()
	{
		val t = SubtitleTracker(confirmFrames = 1, clearFrames = 2)
		t.onFrame("Vamos embora")
		assertTrue(t.onFrame("").isEmpty())
		assertTrue(t.onFrame("Vamos embora").isEmpty())
		assertTrue(t.onFrame("").isEmpty())      // contagem de vazios recomeçou
		assertEquals(1, t.activeSubtitleId)
	}

	@Test fun ocrJitterDoesNotRestart()
	{
		val t = SubtitleTracker(confirmFrames = 1)
		t.onFrame("Eu nunca vou desistir de você")
		val ev = feed(t, "Eu nunca vou desistir de voce", "Eu nunca vou desístir de você", "Eu nunca vou desistir de você.")
		assertTrue(ev.isEmpty())
	}

	@Test fun typewriterTextWaitsUntilStable()
	{
		val t = SubtitleTracker(confirmFrames = 2)
		val ev = feed(t, "Eu", "Eu nunca", "Eu nunca vou", "Eu nunca vou desistir", "Eu nunca vou desistir", "Eu nunca vou desistir")
		assertEquals(1, ev.size)
		assertEquals(Event.Start(1, "Eu nunca vou desistir"), ev[0])
	}

	@Test fun newSubtitleReplacesActiveOne()
	{
		val t = SubtitleTracker(confirmFrames = 1)
		t.onFrame("Primeira fala do jogo")
		val ev = t.onFrame("Agora outra fala totalmente diferente")
		assertEquals(listOf(Event.Stop(1), Event.Start(2, "Agora outra fala totalmente diferente")), ev)
	}

	@Test fun lineDisappearingKeepsSpeaking()
	{
		val t = SubtitleTracker(confirmFrames = 1)
		t.onFrame("Cuidado com o chão. Ele está quebrado")
		assertTrue(t.onFrame("Ele está quebrado").isEmpty())
	}

	@Test fun shortNoiseIsIgnored()
	{
		val t = SubtitleTracker(confirmFrames = 1)
		assertTrue(t.onFrame("I").isEmpty())
		assertTrue(t.onFrame("..").isEmpty())
	}

	@Test fun sameTextAgainAfterGapIsNewSubtitle()
	{
		val t = SubtitleTracker(confirmFrames = 1, clearFrames = 1)
		assertEquals(listOf<Event>(Event.Start(1, "Sim, senhor")), t.onFrame("Sim, senhor"))
		assertEquals(listOf<Event>(Event.Stop(1)), t.onFrame(""))
		assertEquals(listOf<Event>(Event.Start(2, "Sim, senhor")), t.onFrame("Sim, senhor"))
	}

	@Test fun resetStopsActive()
	{
		val t = SubtitleTracker(confirmFrames = 1)
		t.onFrame("Vamos embora")
		assertEquals(listOf<Event>(Event.Stop(1)), t.reset())
		assertTrue(t.reset().isEmpty())
	}
}
