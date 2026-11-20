// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.dub

import android.content.Context
import android.graphics.RectF
import androidx.preference.PreferenceManager

/** Configurações da dublagem (chaves iguais às de res/xml/preferences.xml). */
class DubPreferences(context: Context)
{
	companion object
	{
		const val KEY_ENABLED = "dub_enabled"
		const val KEY_MODE = "dub_mode"
		const val KEY_SERVER_URL = "dub_server_url"
		const val KEY_TOKEN = "dub_token"
		const val KEY_GAME = "dub_game"
		const val KEY_REGION_LEFT = "dub_region_left"
		const val KEY_REGION_TOP = "dub_region_top"
		const val KEY_REGION_WIDTH = "dub_region_width"
		const val KEY_REGION_HEIGHT = "dub_region_height"
		const val KEY_TEXT_MODE = "dub_text_mode"
		const val KEY_CONFIRM_FRAMES = "dub_confirm_frames"
		const val KEY_DUCK_PERCENT = "dub_duck_percent"
		const val KEY_INTERVAL_MS = "dub_interval_ms"
		const val KEY_SHOW_REGION = "dub_show_region"

		const val MODE_LOCAL = "local"
		const val MODE_SERVER = "server"

		const val TEXT_MODE_WHITE = "white"
		const val TEXT_MODE_ANY = "any"
	}

	private val sp = PreferenceManager.getDefaultSharedPreferences(context)

	var enabled: Boolean
		get() = sp.getBoolean(KEY_ENABLED, false)
		set(value) { sp.edit().putBoolean(KEY_ENABLED, value).apply() }

	/** "local" = voz do Android (grátis, sem servidor); "server" = servidor com voz clonada. */
	val mode: String get() = sp.getString(KEY_MODE, MODE_LOCAL) ?: MODE_LOCAL

	val serverUrl: String get() = sp.getString(KEY_SERVER_URL, "")?.trim().orEmpty()
	val token: String get() = sp.getString(KEY_TOKEN, "")?.trim().orEmpty()
	val game: String get() = sp.getString(KEY_GAME, "jogo")?.trim().orEmpty().ifEmpty { "jogo" }

	/** Recorte da legenda como fração da imagem do jogo (0..1). */
	val region: RectF get()
	{
		val l = sp.getInt(KEY_REGION_LEFT, 10) / 100f
		val t = sp.getInt(KEY_REGION_TOP, 74) / 100f
		val w = sp.getInt(KEY_REGION_WIDTH, 80) / 100f
		val h = sp.getInt(KEY_REGION_HEIGHT, 22) / 100f
		val left = l.coerceIn(0f, 0.95f)
		val top = t.coerceIn(0f, 0.95f)
		return RectF(left, top, (left + w).coerceIn(left + 0.05f, 1f), (top + h).coerceIn(top + 0.05f, 1f))
	}

	/** Guarda a área marcada com o dedo (as mesmas chaves das barras em Ajustes). */
	fun saveRegion(r: RectF)
	{
		fun pct(v: Float, lo: Int, hi: Int) = Math.round(v * 100f).coerceIn(lo, hi)
		sp.edit()
			.putInt(KEY_REGION_LEFT, pct(r.left, 0, 95))
			.putInt(KEY_REGION_TOP, pct(r.top, 0, 95))
			.putInt(KEY_REGION_WIDTH, pct(r.width(), 5, 100))
			.putInt(KEY_REGION_HEIGHT, pct(r.height(), 3, 100))
			.apply()
	}

	val textMode: String get() = sp.getString(KEY_TEXT_MODE, TEXT_MODE_WHITE) ?: TEXT_MODE_WHITE
	val confirmFrames: Int get() = sp.getInt(KEY_CONFIRM_FRAMES, 2).coerceIn(1, 4)
	/** Volume do jogo enquanto a voz dublada fala (0..1). */
	val duckGain: Float get() = sp.getInt(KEY_DUCK_PERCENT, 20).coerceIn(0, 100) / 100f
	val intervalMs: Long get() = sp.getInt(KEY_INTERVAL_MS, 100).coerceIn(30, 500).toLong()
	val showRegion: Boolean get() = sp.getBoolean(KEY_SHOW_REGION, false)
}
