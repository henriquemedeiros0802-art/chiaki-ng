// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.dub

import org.junit.Assert.*
import org.junit.Test

class AudioConvertTest
{
	@Test fun downsamplerStereo48kToMono16k()
	{
		val d = MonoDownsampler(48000, 2, 16000)
		val stereo = ShortArray(4800 * 2) { if(it % 2 == 0) 1000 else 3000 }     // 100 ms
		val out = d.process(stereo, stereo.size)
		assertTrue(out.size in 1598..1602)
		assertTrue(out.all { it.toInt() == 2000 })                                 // média dos canais
	}

	@Test fun downsamplerKeepsStateAcrossChunks()
	{
		val d = MonoDownsampler(48000, 2, 16000)
		var total = 0
		repeat(50) { total += d.process(ShortArray(481 * 2) { 5 }, 481 * 2).size }  // chunks "quebrados"
		assertTrue(total in 8014..8018)             // 50 * 481 / 3 ≈ 8016,7 → a proporção se mantém entre blocos
	}

	@Test fun resamplerDoublesLengthAndKeepsConstant()
	{
		val r = LinearResampler(24000, 48000)
		val out = r.process(ShortArray(2400) { 1234 })
		assertTrue(out.size in 4798..4802)
		assertTrue(out.all { it.toInt() == 1234 })
	}

	@Test fun resamplerInterpolatesRamp()
	{
		val r = LinearResampler(1000, 2000)
		val out = r.process(shortArrayOf(0, 100, 200, 300))
		// 0, 0, 50, 100, 150, 200, 250 ... sobe de forma monótona e suave
		for(i in 1 until out.size) assertTrue(out[i] >= out[i - 1])
		assertTrue(out.contains(50.toShort()))
		assertTrue(out.contains(150.toShort()))
	}

	@Test fun resamplerContinuityBetweenChunks()
	{
		val whole = LinearResampler(24000, 48000).process(ShortArray(100) { (it * 10).toShort() })
		val r = LinearResampler(24000, 48000)
		val a = r.process(ShortArray(40) { (it * 10).toShort() })
		val b = r.process(ShortArray(60) { ((it + 40) * 10).toShort() })
		assertArrayEquals(whole, a + b)
	}

	@Test fun pcmRoundTrip()
	{
		val s = shortArrayOf(0, 1, -1, 32767, -32768, 12345)
		assertArrayEquals(s, Pcm.toShorts(Pcm.toBytes(s)))
	}
}
