// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.dub

import org.junit.Assert.*
import org.junit.Test

class RegionAnalyzerTest
{
	private val w = 200
	private val h = 40
	private fun solid(argb: Int) = IntArray(w * h) { argb }

	private fun withWhiteText(bg: Int): IntArray
	{
		val px = solid(bg)
		for(y in 15..24) for(x in 40..160) if((x / 3 + y) % 2 == 0) px[y * w + x] = 0xFFFFFFFF.toInt()
		return px
	}

	@Test fun darkSceneHasNoWhiteText()
	{
		val r = RegionAnalyzer().analyze(solid(0xFF202020.toInt()), w, h)
		assertFalse(r.whiteTextLikely)
	}

	@Test fun whiteTextOnDarkIsDetected()
	{
		val r = RegionAnalyzer().analyze(withWhiteText(0xFF303030.toInt()), w, h)
		assertTrue(r.whiteTextLikely)
		assertTrue(r.brightRatio > 0.05f)
	}

	@Test fun fingerprintDetectsChange()
	{
		val a = RegionAnalyzer()
		val f1 = a.analyze(withWhiteText(0xFF303030.toInt()), w, h).fingerprint
		val same = a.analyze(withWhiteText(0xFF303030.toInt()), w, h).fingerprint
		val f2 = a.analyze(solid(0xFF303030.toInt()), w, h).fingerprint
		assertEquals(0f, a.difference(f1, same), 0f)
		assertTrue(a.difference(f1, f2) > 1f)
		assertEquals(255f, a.difference(null, f1), 0f)
	}
}
