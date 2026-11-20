// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.dub

/** JSON mínimo para as mensagens (objetos planos com texto, número, booleano e null). */
object MiniJson
{
	fun encode(fields: Map<String, Any?>): String
	{
		val sb = StringBuilder("{")
		var first = true
		for((k, v) in fields)
		{
			if(!first) sb.append(',')
			first = false
			quote(k, sb); sb.append(':')
			when(v)
			{
				null -> sb.append("null")
				is Boolean, is Int, is Long -> sb.append(v.toString())
				is Number -> sb.append(v.toString())
				else -> quote(v.toString(), sb)
			}
		}
		return sb.append('}').toString()
	}

	private fun quote(s: String, sb: StringBuilder)
	{
		sb.append('"')
		for(ch in s)
		{
			when(ch)
			{
				'"' -> sb.append("\\\"")
				'\\' -> sb.append("\\\\")
				'\n' -> sb.append("\\n")
				'\r' -> sb.append("\\r")
				'\t' -> sb.append("\\t")
				else -> if(ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
			}
		}
		sb.append('"')
	}

	/** Decodifica um objeto plano. Devolve null se não for um objeto JSON válido. */
	fun decode(text: String): Map<String, Any?>?
	{
		return try { Parser(text).parseObject() } catch(e: IllegalArgumentException) { null }
	}

	private class Parser(val s: String)
	{
		var i = 0

		fun parseObject(): Map<String, Any?>
		{
			ws(); expect('{')
			val map = LinkedHashMap<String, Any?>()
			ws()
			if(peek() == '}') { i++; return map }
			while(true)
			{
				ws()
				val key = parseString()
				ws(); expect(':'); ws()
				map[key] = parseValue()
				ws()
				when(next())
				{
					',' -> continue
					'}' -> return map
					else -> throw IllegalArgumentException("esperava , ou }")
				}
			}
		}

		fun parseValue(): Any? = when(val c = peek())
		{
			'"' -> parseString()
			't' -> literal("true", true)
			'f' -> literal("false", false)
			'n' -> literal("null", null)
			else -> if(c == '-' || c.isDigit()) parseNumber() else throw IllegalArgumentException("valor inesperado")
		}

		fun literal(word: String, value: Any?): Any?
		{
			if(!s.startsWith(word, i)) throw IllegalArgumentException("literal inválido")
			i += word.length
			return value
		}

		fun parseNumber(): Any
		{
			val start = i
			while(i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
			val t = s.substring(start, i)
			return t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw IllegalArgumentException("número inválido")
		}

		fun parseString(): String
		{
			expect('"')
			val sb = StringBuilder()
			while(true)
			{
				if(i >= s.length) throw IllegalArgumentException("string não terminada")
				val c = s[i++]
				when(c)
				{
					'"' -> return sb.toString()
					'\\' ->
					{
						when(val e = next())
						{
							'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
							'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
							'/', '\\', '"' -> sb.append(e)
							'u' ->
							{
								if(i + 4 > s.length) throw IllegalArgumentException("\\u inválido")
								sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
							}
							else -> throw IllegalArgumentException("escape inválido")
						}
					}
					else -> sb.append(c)
				}
			}
		}

		fun ws() { while(i < s.length && s[i].isWhitespace()) i++ }
		fun peek(): Char = if(i < s.length) s[i] else throw IllegalArgumentException("fim inesperado")
		fun next(): Char = peek().also { i++ }
		fun expect(c: Char) { if(next() != c) throw IllegalArgumentException("esperava $c") }
	}
}

/** Mensagens servidor → app. */
sealed class DubMessage
{
	data class Ready(val rate: Int): DubMessage()
	data class Speaker(val id: Int, val speaker: String, val cloned: Boolean): DubMessage()
	data class AudioStart(val id: Int, val rate: Int, val latencyMs: Int): DubMessage()
	data class AudioEnd(val id: Int): DubMessage()
	data class Stopped(val id: Int): DubMessage()
	data class Error(val id: Int, val message: String): DubMessage()
	class Audio(val id: Int, val pcm: ShortArray): DubMessage()

	companion object
	{
		private fun Map<String, Any?>.int(key: String): Int = (this[key] as? Number)?.toInt() ?: 0

		fun parseText(text: String): DubMessage?
		{
			val m = MiniJson.decode(text) ?: return null
			return when(m["type"])
			{
				"ready" -> Ready(m.int("rate"))
				"speaker" -> Speaker(m.int("id"), m["speaker"]?.toString() ?: "?", m["cloned"] == true)
				"audio_start" -> AudioStart(m.int("id"), m.int("rate"), m.int("latency_ms"))
				"audio_end" -> AudioEnd(m.int("id"))
				"stopped" -> Stopped(m.int("id"))
				"error" -> Error(m.int("id"), m["message"]?.toString() ?: "")
				else -> null
			}
		}

		/** Quadro binário: 0x01, id (4 bytes, big-endian), PCM16 little-endian. */
		fun parseBinary(bytes: ByteArray): Audio?
		{
			if(bytes.size < 5 || bytes[0].toInt() != 1)
				return null
			val id = ((bytes[1].toInt() and 0xFF) shl 24) or ((bytes[2].toInt() and 0xFF) shl 16) or
				((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
			return Audio(id, Pcm.toShorts(bytes, 5, bytes.size - 5))
		}

		fun subtitle(id: Int, text: String) = MiniJson.encode(mapOf("type" to "subtitle", "id" to id, "text" to text))
		fun stop(id: Int) = MiniJson.encode(mapOf("type" to "stop", "id" to id))
	}
}

object DubUrl
{
	/** Aceita "192.168.0.10:8765", "http(s)://..." ou "ws(s)://..." e monta a URL do WebSocket. */
	fun build(base: String, token: String, game: String): String
	{
		var b = base.trim().trimEnd('/')
		b = when
		{
			b.startsWith("wss://") || b.startsWith("ws://") -> b
			b.startsWith("https://") -> "wss://" + b.removePrefix("https://")
			b.startsWith("http://") -> "ws://" + b.removePrefix("http://")
			else -> "ws://$b"
		}
		val enc = { v: String -> java.net.URLEncoder.encode(v, "UTF-8") }
		return b + "/ws?game=" + enc(game) + (if(token.isNotEmpty()) "&token=" + enc(token) else "")
	}
}
