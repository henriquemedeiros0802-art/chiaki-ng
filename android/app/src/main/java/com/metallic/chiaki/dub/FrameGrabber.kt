// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.dub

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Copia só o recorte da legenda do SurfaceView (que está recebendo o vídeo decodificado em hardware).
 * É uma cópia da NOSSA própria tela, não captura de tela do sistema, então não precisa de permissão
 * de gravação de tela.
 */
@RequiresApi(Build.VERSION_CODES.O)
class FrameGrabber(private val view: SurfaceView)
{
	private val thread = HandlerThread("dub-pixelcopy").also { it.start() }
	private val handler = Handler(thread.looper)
	private var bitmap: Bitmap? = null

	/** [region] em frações da imagem. Devolve um bitmap reutilizado (não guarde a referência). */
	suspend fun grab(region: RectF, maxWidth: Int): Bitmap?
	{
		val frame = view.holder.surfaceFrame
		val w = frame.width()
		val h = frame.height()
		if(w <= 0 || h <= 0 || !view.holder.surface.isValid)
			return null
		val src = Rect(
			(region.left * w).toInt(), (region.top * h).toInt(),
			(region.right * w).toInt().coerceAtMost(w), (region.bottom * h).toInt().coerceAtMost(h))
		if(src.width() < 8 || src.height() < 8)
			return null
		// legendas são pequenas: amplia até 2x para o OCR enxergar melhor
		val dw = minOf(src.width() * 3, maxWidth)
		val dh = maxOf(8, src.height() * dw / src.width())
		var b = bitmap
		if(b == null || b.width != dw || b.height != dh)
		{
			b = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
			bitmap = b
		}
		val target = b!!
		return suspendCancellableCoroutine { cont ->
			try
			{
				PixelCopy.request(view, src, target, { result ->
					if(cont.isActive)
						cont.resume(if(result == PixelCopy.SUCCESS) target else null)
				}, handler)
			}
			catch(e: IllegalArgumentException)
			{
				if(cont.isActive)
					cont.resume(null)
			}
		}
	}

	fun release()
	{
		thread.quitSafely()
		bitmap = null
	}
}
