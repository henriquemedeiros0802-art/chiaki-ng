// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.dub

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Moldura sobre o vídeo que mostra qual área é lida como legenda.
 * Em modo editável o usuário arrasta o retângulo e puxa os cantos para marcar a legenda.
 */
class RegionOutlineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null): View(context, attrs)
{
	var target: View? = null

	/** Área da legenda em frações (0..1) da imagem do vídeo. */
	var region: RectF = RectF(0.1f, 0.74f, 0.9f, 0.96f)
		set(value) { field = value; invalidate() }

	var editable = false
		set(value)
		{
			field = value
			isClickable = value          // só intercepta toques quando está editando
			invalidate()
		}

	/** Chamado ao soltar o dedo, com a nova área. */
	var onRegionChanged: ((RectF) -> Unit)? = null

	private val density = resources.displayMetrics.density
	private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = 4f
		color = 0xFF00B0FF.toInt()
	}
	private val fill = Paint().apply { style = Paint.Style.FILL; color = 0x3300B0FF }
	private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt() }
	private val handleRadius = 10f * density
	private val grabRadius = 36f * density

	private enum class Mode { NONE, MOVE, RESIZE }
	private var mode = Mode.NONE
	private var anchorX = 0f
	private var anchorY = 0f
	private var grabDx = 0f
	private var grabDy = 0f
	private var startRegion = RectF()

	private fun targetRect(): RectF?
	{
		val t = target ?: return null
		if(t.width <= 0 || t.height <= 0)
			return null
		val a = IntArray(2)
		val b = IntArray(2)
		t.getLocationInWindow(a)
		getLocationInWindow(b)
		val l = (a[0] - b[0]).toFloat()
		val tp = (a[1] - b[1]).toFloat()
		return RectF(l, tp, l + t.width, tp + t.height)
	}

	private fun toPixels(t: RectF, r: RectF) = RectF(
		t.left + r.left * t.width(), t.top + r.top * t.height(),
		t.left + r.right * t.width(), t.top + r.bottom * t.height())

	override fun onDraw(canvas: Canvas)
	{
		val t = targetRect() ?: return
		val r = toPixels(t, region)
		if(editable)
			canvas.drawRect(r, fill)
		canvas.drawRect(r, stroke)
		if(editable)
			for((x, y) in corners(r))
				canvas.drawCircle(x, y, handleRadius, handle)
	}

	private fun corners(r: RectF) = listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)

	override fun onTouchEvent(e: MotionEvent): Boolean
	{
		if(!editable)
			return false
		val t = targetRect() ?: return false
		val x = e.x.coerceIn(t.left, t.right)
		val y = e.y.coerceIn(t.top, t.bottom)

		when(e.actionMasked)
		{
			MotionEvent.ACTION_DOWN ->
			{
				startRegion = RectF(region)
				val r = toPixels(t, region)
				val corner = corners(r).firstOrNull { (cx, cy) -> abs(cx - e.x) < grabRadius && abs(cy - e.y) < grabRadius }
				when
				{
					corner != null ->
					{
						// canto puxado: o canto oposto fica parado
						anchorX = if(corner.first == r.left) r.right else r.left
						anchorY = if(corner.second == r.top) r.bottom else r.top
						mode = Mode.RESIZE
					}
					r.contains(e.x, e.y) ->
					{
						grabDx = e.x - r.left
						grabDy = e.y - r.top
						mode = Mode.MOVE
					}
					else ->
					{
						// toque fora: começa um retângulo novo a partir deste ponto
						anchorX = x
						anchorY = y
						mode = Mode.RESIZE
					}
				}
				return true
			}
			MotionEvent.ACTION_MOVE ->
			{
				val r = toPixels(t, region)
				val next = when(mode)
				{
					Mode.MOVE ->
					{
						val w = r.width()
						val h = r.height()
						val l = max(t.left, min(e.x - grabDx, t.right - w))
						val tp = max(t.top, min(e.y - grabDy, t.bottom - h))
						RectF(l, tp, l + w, tp + h)
					}
					Mode.RESIZE -> RectF(min(anchorX, x), min(anchorY, y), max(anchorX, x), max(anchorY, y))
					Mode.NONE -> return true
				}
				region = RectF(
					(next.left - t.left) / t.width(), (next.top - t.top) / t.height(),
					(next.right - t.left) / t.width(), (next.bottom - t.top) / t.height())
				return true
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
			{
				if(mode != Mode.NONE)
				{
					if(region.width() < 0.05f || region.height() < 0.03f)
						region = startRegion              // retângulo pequeno demais: desfaz
					else
						onRegionChanged?.invoke(RectF(region))
				}
				mode = Mode.NONE
				return true
			}
		}
		return true
	}
}
