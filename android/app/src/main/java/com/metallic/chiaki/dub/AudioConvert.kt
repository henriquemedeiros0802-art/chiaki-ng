// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.dub

/**
 * Converte o áudio intercalado do jogo (ex.: estéreo 48 kHz) para mono em [outRate] (16 kHz),
 * em fluxo contínuo (mantém o estado entre chamadas). Filtro simples de média: suficiente para
 * detectar voz e identificar o falante, não para ouvir.
 */
class MonoDownsampler(private val inRate: Int, private val channels: Int, private val outRate: Int = 16000)
{
	private val step = inRate.toDouble() / outRate
	private var phase = 0.0
	private var acc = 0L
	private var accN = 0

	/** Retorna amostras mono PCM16 em [outRate]. [count] = amostras intercaladas válidas em [src]. */
	fun process(src: ShortArray, count: Int): ShortArray
	{
		val frames = count / channels
		val out = ShortArray((frames / step).toInt() + 2)
		var o = 0
		for(f in 0 until frames)
		{
			var mono = 0
			for(c in 0 until channels)
				mono += src[f * channels + c]
			acc += mono / channels
			accN++
			phase += 1.0
			if(phase >= step)
			{
				out[o++] = (acc / accN).toShort()
				acc = 0
				accN = 0
				phase -= step
			}
		}
		return out.copyOf(o)
	}
}

/** Reamostrador linear mono em fluxo contínuo (ex.: 24 kHz da voz → 48 kHz do stream). */
class LinearResampler(inRate: Int, outRate: Int)
{
	private val step = inRate.toDouble() / outRate
	private var last = 0.0
	private var frac = 0.0
	private var started = false

	fun process(src: ShortArray, count: Int = src.size): ShortArray
	{
		if(count == 0)
			return ShortArray(0)
		val out = ShortArray((count / step).toInt() + 2)
		var o = 0
		for(i in 0 until count)
		{
			val cur = src[i].toDouble()
			if(!started)
			{
				last = cur
				started = true
			}
			while(frac < 1.0)
			{
				out[o++] = (last + (cur - last) * frac).toInt().toShort()
				frac += step
			}
			frac -= 1.0
			last = cur
		}
		return out.copyOf(o)
	}
}

object Pcm
{
	/** PCM16 little-endian → ShortArray. */
	fun toShorts(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): ShortArray
	{
		val n = length / 2
		val out = ShortArray(n)
		for(i in 0 until n)
		{
			val lo = bytes[offset + 2 * i].toInt() and 0xFF
			val hi = bytes[offset + 2 * i + 1].toInt()
			out[i] = ((hi shl 8) or lo).toShort()
		}
		return out
	}

	fun toBytes(samples: ShortArray, count: Int = samples.size): ByteArray
	{
		val out = ByteArray(count * 2)
		for(i in 0 until count)
		{
			out[2 * i] = (samples[i].toInt() and 0xFF).toByte()
			out[2 * i + 1] = (samples[i].toInt() shr 8).toByte()
		}
		return out
	}
}
