package com.mundymorning.couchcast.play

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import com.mundymorning.couchcast.playback.PhotoSize
import java.io.File
import java.io.IOException

/** Re-encodes a picked photo as a JPEG no larger than 1080p, upright, so any Roku can show it. */
object PhotoPrep {
    @Throws(IOException::class)
    fun toJpeg(context: Context, uri: Uri, out: File) {
        out.parentFile?.mkdirs()
        val decoded = try {
            if (Build.VERSION.SDK_INT >= 28) decodeModern(context, uri) else decodeLegacy(context, uri)
        } catch (e: IOException) { throw e } catch (e: Exception) { throw IOException("Couldn't read that picture", e) }
        val flat = flattenOnWhite(decoded)
        try {
            out.outputStream().use { if (!flat.compress(Bitmap.CompressFormat.JPEG, 90, it)) throw IOException("Couldn't prepare that picture") }
        } finally {
            if (flat !== decoded) flat.recycle()
            decoded.recycle()
        }
    }

    private fun decodeModern(context: Context, uri: Uri): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val (w, h) = PhotoSize.fit(info.size.width, info.size.height)
            if (w != info.size.width) decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // a hardware bitmap can't be compressed
        }

    private fun decodeLegacy(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: throw IOException("Couldn't open the picture")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Couldn't read that picture")
        val opts = BitmapFactory.Options().apply { inSampleSize = PhotoSize.sampleSize(bounds.outWidth, bounds.outHeight) }
        var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: throw IOException("Couldn't read that picture")
        val (w, h) = PhotoSize.fit(bounds.outWidth, bounds.outHeight)
        if (bmp.width > w) bmp = Bitmap.createScaledBitmap(bmp, w, h, true)
        val orientation = try {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (_: Exception) { null } ?: ExifInterface.ORIENTATION_NORMAL
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /** JPEG has no transparency; a transparent PNG would otherwise turn black. */
    private fun flattenOnWhite(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val flat = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(src, 0f, 0f, null) }
        return flat
    }
}
