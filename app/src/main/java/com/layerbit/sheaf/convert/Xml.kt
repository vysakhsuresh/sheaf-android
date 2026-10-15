package com.layerbit.sheaf.convert

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The few bits of XML and ZIP every Office and EPUB writer needs. */
internal object Xml {
    const val DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    fun text(s: String): String = buildString(s.length + 8) {
        for (ch in s) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                // Control characters are not allowed in XML 1.0 at all; a tab and a newline are.
                '\t', '\n', '\r' -> append(ch)
                else -> if (ch < ' ') append(' ') else append(ch)
            }
        }
    }

    fun attr(s: String): String = text(s).replace("\"", "&quot;")
}

/** A zip written entry by entry. Office files and EPUBs are zips with particular names in them. */
internal class ZipWriter(file: File) : AutoCloseable {
    private val out = ZipOutputStream(file.outputStream().buffered())

    fun put(name: String, content: String) = put(name, content.toByteArray(Charsets.UTF_8))

    fun put(name: String, bytes: ByteArray) {
        out.putNextEntry(ZipEntry(name))
        out.write(bytes)
        out.closeEntry()
    }

    /**
     * Stored, not deflated, and first: an EPUB's `mimetype` has to be readable from the
     * file's first bytes, which deflating would hide.
     */
    fun putStored(name: String, content: String) {
        val bytes = content.toByteArray(Charsets.US_ASCII)
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        out.putNextEntry(entry)
        out.write(bytes)
        out.closeEntry()
    }

    override fun close() = out.close()
}

/** Reads one entry out of a zip without unpacking the rest. */
internal fun File.zipEntry(name: String): ByteArray? =
    java.util.zip.ZipFile(this).use { zip ->
        val entry = zip.getEntry(name) ?: return null
        zip.getInputStream(entry).use { input ->
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            out.toByteArray()
        }
    }

/** The names in a zip, for finding the sheets and the media. */
internal fun File.zipNames(): List<String> =
    java.util.zip.ZipFile(this).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
