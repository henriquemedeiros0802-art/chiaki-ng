// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.dub

/**
 * Análise barata (sem OCR) do recorte da legenda:
 *  - "tem texto claro?" → permite concluir em 1 quadro que a legenda sumiu, sem rodar OCR;
 *  - impressão digital do recorte → permite pular o OCR quando nada mudou.
 */
class RegionAnalyzer(
	private val brightMin: Int = 150,
	private val minBrightRatio: Float = 0.003f,
	private val gridW: Int = 32,
	private val gridH: Int = 8
)
{
	class Result(val brightRatio: Float, val whiteTextLikely: Boolean, val fingerprint: IntArray)

	/** pixels em ARGB_8888 (como Bitmap.getPixels). */
	fun analyze(pixels: IntArray, width: Int, height: Int): Result
	{
		var bright = 0
		val sums = LongArray(gridW * gridH)
		val counts = IntArray(gridW * gridH)
		for(y in 0 until height)
		{
			val gy = y * gridH / height
			val row = y * width
			for(x in 0 until width)
			{
				val p = pixels[row + x]
				val r = (p shr 16) and 0xFF
				val g = (p shr 8) and 0xFF
				val b = p and 0xFF
				if(r >= brightMin && g >= brightMin && b >= brightMin)
					bright++
				val lum = (77 * r + 150 * g + 29 * b) shr 8
				val cell = gy * gridW + x * gridW / width
				sums[cell] += lum.toLong()
				counts[cell]++
			}
		}
		val total = (width * height).coerceAtLeast(1)
		val ratio = bright.toFloat() / total
		val fp = IntArray(gridW * gridH) { if(counts[it] > 0) (sums[it] / counts[it]).toInt() else 0 }
		return Result(ratio, ratio >= minBrightRatio, fp)
	}

	/** Diferença média absoluta entre duas impressões digitais (0..255). */
	fun difference(a: IntArray?, b: IntArray): Float
	{
		if(a == null || a.size != b.size)
			return 255f
		var sum = 0L
		for(i in a.indices)
			sum += kotlin.math.abs(a[i] - b[i])
		return sum.toFloat() / a.size
	}
}
