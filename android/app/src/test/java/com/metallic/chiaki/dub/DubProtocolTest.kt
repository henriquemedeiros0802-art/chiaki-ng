// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.dub

import org.junit.Assert.*
import org.junit.Test

class DubProtocolTest
{
	@Test fun encodeEscapesText()
	{
		val s = DubMessage.subtitle(7, "Ele disse \"oi\"\ne\\saiu")
		val back = MiniJson.decode(s)!!
		assertEquals("subtitle", back["type"])
		assertEquals(7L, back["id"])
		assertEquals("Ele disse \"oi\"\ne\\saiu", back["text"])
	}

	@Test fun unicodeAndEmoji()
	{
		val s = DubMessage.subtitle(1, "Coração ♪ ação 日本")
		assertEquals("Coração ♪ ação 日本", MiniJson.decode(s)!!["text"])
	}

	@Test fun parsesServerMessages()
	{
		assertEquals(DubMessage.Ready(24000), DubMessage.parseText("""{"type":"ready","rate":24000,"game":"x"}"""))
		assertEquals(DubMessage.Speaker(3, "S2", true), DubMessage.parseText("""{"type":"speaker","id":3,"speaker":"S2","cloned":true}"""))
		assertEquals(DubMessage.AudioStart(3, 24000, 412), DubMessage.parseText("""{"type":"audio_start","id":3,"rate":24000,"latency_ms":412}"""))
		assertEquals(DubMessage.AudioEnd(3), DubMessage.parseText("""{"type":"audio_end","id":3}"""))
		assertEquals(DubMessage.Stopped(3), DubMessage.parseText("""{"type":"stopped","id":3}"""))
		assertEquals(DubMessage.Error(3, "x"), DubMessage.parseText("""{"type":"error","id":3,"message":"x"}"""))
		assertNull(DubMessage.parseText("""{"type":"pong"}"""))
		assertNull(DubMessage.parseText("isto não é json"))
		assertNull(DubMessage.parseText("""{"type":"""))
	}

	@Test fun parsesBinaryAudioFrame()
	{
		val pcm = shortArrayOf(1, -2, 300)
		val frame = byteArrayOf(1, 0, 0, 1, 2) + Pcm.toBytes(pcm)      // id = 0x0102 = 258
		val a = DubMessage.parseBinary(frame)!!
		assertEquals(258, a.id)
		assertArrayEquals(pcm, a.pcm)
		assertNull(DubMessage.parseBinary(byteArrayOf(2, 0, 0, 0, 1)))   // tipo desconhecido
		assertNull(DubMessage.parseBinary(byteArrayOf(1, 0)))            // curto demais
	}

	@Test fun buildsWebSocketUrl()
	{
		assertEquals("ws://192.168.0.10:8765/ws?game=jogo", DubUrl.build(" 192.168.0.10:8765/ ", "", "jogo"))
		assertEquals("wss://dub.exemplo.com/ws?game=a+b&token=x%26y", DubUrl.build("https://dub.exemplo.com", "x&y", "a b"))
		assertEquals("ws://h/ws?game=g", DubUrl.build("http://h", "", "g"))
		assertEquals("wss://h/ws?game=g&token=t", DubUrl.build("wss://h/", "t", "g"))
	}
}
