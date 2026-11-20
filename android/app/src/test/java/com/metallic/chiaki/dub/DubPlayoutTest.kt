// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.dub

import org.junit.Assert.*
import org.junit.Test

class DubPlayoutTest
{
	private class Sink(var capacity: Int)
	{
		val written = ArrayList<Short>()
		fun write(a: ShortArray, off: Int, n: Int): Int
		{
			val k = minOf(n, capacity)
			for(i in 0 until k) written.add(a[off + i])
			capacity -= k
			return k
		}
	}

	@Test fun holdsAudioUntilNativeBufferHasRoom()
	{
		var now = 0L
		val p = DubPlayout { now }
		p.begin(1, 48000)
		assertTrue(p.enqueue(1, ShortArray(1000) { it.toShort() }))
		val sink = Sink(400)
		p.pump(sink::write)
		assertEquals(400, sink.written.size)
		assertEquals(600, p.queuedSamples())
		sink.capacity = 10_000
		p.pump(sink::write)
		assertEquals(1000, sink.written.size)
		assertEquals((0 until 1000).map { it.toShort() }, sink.written)    // ordem preservada
		assertEquals(0, p.queuedSamples())
	}

	@Test fun staleIdIsRejected()
	{
		val p = DubPlayout()
		p.begin(2, 48000)
		assertFalse(p.enqueue(1, ShortArray(10)))
		assertTrue(p.enqueue(2, ShortArray(10)))
	}

	@Test fun cancelDropsQueueAndFlushesAtomically()
	{
		val p = DubPlayout()
		p.begin(1, 48000)
		p.enqueue(1, ShortArray(500))
		var flushed = 0
		p.cancel { flushed++ }
		assertEquals(1, flushed)
		assertEquals(0, p.queuedSamples())
		assertFalse(p.enqueue(1, ShortArray(10)))          // chegou atrasado: descartado
		assertEquals(0, p.activeId)
	}

	@Test fun finishedOnlyAfterEndQueueEmptyAndPlayoutDone()
	{
		var now = 1000L
		val p = DubPlayout { now }
		p.begin(1, 48000)
		p.enqueue(1, ShortArray(4800))                     // 100 ms
		assertFalse(p.isFinished())
		p.markEnd(1)
		assertFalse(p.isFinished())                         // ainda na fila
		p.pump { _, _, n -> n }
		assertFalse(p.isFinished())                         // escrito, mas ainda tocando
		now += 100
		assertFalse(p.isFinished())                         // falta a margem de 120 ms
		now += 130
		assertTrue(p.isFinished())
	}

	@Test fun playoutEstimateAccumulatesAcrossWrites()
	{
		var now = 0L
		val p = DubPlayout { now }
		p.begin(1, 48000)
		p.enqueue(1, ShortArray(48000)); p.pump { _, _, n -> n }     // 1 s
		now += 500
		p.enqueue(1, ShortArray(48000)); p.pump { _, _, n -> n }     // +1 s → termina em t=2000
		p.markEnd(1)
		now = 1900
		assertFalse(p.isFinished())
		now = 2200
		assertTrue(p.isFinished())
	}
}
