// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.dub

/**
 * Fila entre a rede e o buffer nativo de áudio. O servidor entrega a voz MAIS RÁPIDO que o tempo
 * real, então ela espera aqui e vai para o buffer nativo conforme houver espaço. Também descarta
 * trechos de falas já canceladas (id antigo) e estima quando o último som termina de tocar.
 */
class DubPlayout(private val nowMs: () -> Long = System::currentTimeMillis)
{
	private val lock = Any()
	private val queue = ArrayDeque<ShortArray>()
	private var headOffset = 0
	private var currentId = 0
	private var outRate = 48000
	private var playoutEndMs = 0L
	private var endReceived = false

	val activeId: Int get() = synchronized(lock) { currentId }

	fun begin(id: Int, streamRate: Int)
	{
		synchronized(lock)
		{
			queue.clear(); headOffset = 0
			currentId = id
			outRate = streamRate
			endReceived = false
		}
	}

	/** Descarta tudo e ignora qualquer áudio que ainda chegue para o id antigo. */
	fun cancel(flushNative: () -> Unit)
	{
		synchronized(lock)
		{
			queue.clear(); headOffset = 0
			currentId = 0
			endReceived = false
			playoutEndMs = 0
			flushNative()      // dentro do lock: nenhuma escrita pode entrar entre o descarte e o flush
		}
	}

	fun enqueue(id: Int, samples: ShortArray): Boolean
	{
		if(samples.isEmpty())
			return false
		synchronized(lock)
		{
			if(id != currentId)
				return false
			queue.addLast(samples)
			return true
		}
	}

	fun markEnd(id: Int)
	{
		synchronized(lock) { if(id == currentId) endReceived = true }
	}

	/** Passa o que couber para o buffer nativo. [write] devolve quantas amostras foram aceitas. */
	fun pump(write: (ShortArray, Int, Int) -> Int)
	{
		synchronized(lock)
		{
			while(queue.isNotEmpty())
			{
				val head = queue.first()
				val remaining = head.size - headOffset
				val wrote = write(head, headOffset, remaining)
				if(wrote > 0)
				{
					val now = nowMs()
					playoutEndMs = maxOf(playoutEndMs, now) + wrote * 1000L / outRate
				}
				headOffset += wrote
				if(headOffset >= head.size)
				{
					queue.removeFirst()
					headOffset = 0
				}
				else
					break      // buffer nativo cheio
			}
		}
	}

	/** Verdadeiro quando o servidor terminou, a fila esvaziou e o último som já tocou (+ margem). */
	fun isFinished(tailMs: Long = 120): Boolean
	{
		synchronized(lock)
		{
			return endReceived && queue.isEmpty() && nowMs() >= playoutEndMs + tailMs
		}
	}

	fun queuedSamples(): Int = synchronized(lock) { queue.sumOf { it.size } - headOffset }
}
