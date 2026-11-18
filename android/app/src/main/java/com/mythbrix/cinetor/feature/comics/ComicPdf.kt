package com.mythbrix.cinetor.feature.comics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import java.io.File

/** Builds one PDF from a chapter's page images, using only Android's built-in [PdfDocument]. */
object ComicPdf {
    /** Page width in PDF points (A4 width); heights follow each image's aspect ratio. */
    private const val PAGE_WIDTH = 595

    /** Longest page, in points, before a tall webtoon strip is cut into several PDF pages. */
    private const val MAX_PAGE_HEIGHT = 1_400

    /** Decoded width cap in pixels: sharp on any phone, keeps the file and memory small. */
    private const val MAX_DECODE_WIDTH = 1_240

    /**
     * Pure layout: how a [widthPx] × [heightPx] image splits into PDF pages. Each entry is the source
     * pixel rows `top until bottom`; a normal page is a single slice, a long strip several.
     */
    fun slices(widthPx: Int, heightPx: Int): List<IntRange> {
        if (widthPx <= 0 || heightPx <= 0) return emptyList()
        val scale = PAGE_WIDTH.toFloat() / widthPx
        val sliceRows = (MAX_PAGE_HEIGHT / scale).toInt().coerceAtLeast(1)
        return (0 until heightPx step sliceRows).map { top -> top until minOf(top + sliceRows, heightPx) }
    }

    /** Writes [pages] (image files, in reading order) to [out]; returns false when nothing could be added. */
    fun write(pages: List<File>, out: File): Boolean {
        val doc = PdfDocument()
        var added = 0
        try {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            for (file in pages) {
                val bmp = decode(file) ?: continue
                try {
                    for (rows in slices(bmp.width, bmp.height)) {
                        val scale = PAGE_WIDTH.toFloat() / bmp.width
                        val h = (rows.count() * scale).toInt().coerceAtLeast(1)
                        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, h, ++added).create())
                        page.canvas.drawBitmap(bmp, Rect(0, rows.first, bmp.width, rows.last + 1), Rect(0, 0, PAGE_WIDTH, h), paint)
                        doc.finishPage(page)
                    }
                } finally {
                    bmp.recycle()
                }
            }
            if (added == 0) return false
            out.parentFile?.mkdirs()
            out.outputStream().use { doc.writeTo(it) }
            return true
        } finally {
            doc.close()
        }
    }

    private fun decode(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_DECODE_WIDTH) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}
