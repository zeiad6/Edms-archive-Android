package com.zidex.edms

import com.zidex.edms.data.Arabic
import com.zidex.edms.scan.JpegPdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PureLogicTest {
    @Test
    fun arabicSearchNormalizesAndStems() {
        assertEquals(listOf("مستند"), Arabic.terms("المستندات"))
        assertEquals("احمد مدرسه 123", Arabic.normalize("أحمد مَدرسة ١٢٣"))
        val hay = Arabic.normalize("قرار بتعديل الهيكل التنظيمي للمستندات")
        assertTrue(Arabic.terms("المستندات التنظيمي").all { it in hay })
    }

    @Test
    fun pdfWriterProducesValidXref() {
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val pdf = JpegPdf.build(listOf(JpegPdf.Page(fakeJpeg, 100, 141), JpegPdf.Page(fakeJpeg, 141, 100)))
        val text = String(pdf, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue("/Count 2" in text)
        // startxref must point at the "xref" keyword, and each xref entry at "N 0 obj".
        val xref = text.substringAfterLast("startxref\n").substringBefore('\n').toInt()
        assertTrue(text.startsWith("xref", xref))
        val entries = text.substring(xref).lines().drop(3).takeWhile { it.endsWith(" n ") }
        entries.forEachIndexed { i, e -> assertTrue(text.startsWith("${i + 1} 0 obj", e.take(10).toInt())) }
        assertEquals(8, entries.size) // catalog + pages + 3 objects per page
    }
}
