// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.dub

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Moldura que mostra por cima do vídeo qual área está sendo lida como legenda (para calibrar). */
class RegionOutlineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null): View(context, attrs)
{
	var target: View? = null
	var region: RectF = RectF(0.1f, 0.74f, 0.9f, 0.96f)
		set(value) { field = value; invalidate() }

	private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = 4f
		color = 0xFF00E5FF.toInt()
	}

	override fun onDraw(canvas: Canvas)
	{
		val t = target ?: return
		val a = IntArray(2)
		val b = IntArray(2)
		t.getLocationInWindow(a)
		getLocationInWindow(b)
		val ox = (a[0] - b[0]).toFloat()
		val oy = (a[1] - b[1]).toFloat()
		canvas.drawRect(
			ox + region.left * t.width, oy + region.top * t.height,
			ox + region.right * t.width, oy + region.bottom * t.height, paint)
	}
}
