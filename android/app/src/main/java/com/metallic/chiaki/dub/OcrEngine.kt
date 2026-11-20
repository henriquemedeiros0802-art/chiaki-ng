// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.dub

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** OCR no aparelho (ML Kit, alfabeto latino: português com acentos). */
class OcrEngine
{
	private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

	suspend fun recognize(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
		recognizer.process(InputImage.fromBitmap(bitmap, 0))
			.addOnSuccessListener { if(cont.isActive) cont.resume(it.text.replace('\n', ' ').trim()) }
			.addOnFailureListener { if(cont.isActive) cont.resume("") }
	}

	/** Uma string por linha de texto, de cima para baixo. */
	suspend fun recognizeLines(bitmap: Bitmap): List<String> = suspendCancellableCoroutine { cont ->
		recognizer.process(InputImage.fromBitmap(bitmap, 0))
			.addOnSuccessListener { r ->
				if(cont.isActive)
					cont.resume(r.textBlocks.flatMap { b -> b.lines.map { it.text.trim() } }.filter { it.isNotEmpty() })
			}
			.addOnFailureListener { if(cont.isActive) cont.resume(emptyList()) }
	}

	fun close() = recognizer.close()
}
