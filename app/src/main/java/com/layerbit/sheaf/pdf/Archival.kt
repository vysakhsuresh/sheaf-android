package com.layerbit.sheaf.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDOutputIntent
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The archive copy: what PDF/A-2b asks of a file, done to an open document.
 *
 * An sRGB colour profile inside the file, XMP metadata that says which standard and
 * mirrors the details, a file ID, every annotation printable, no scripts or attached files,
 * no encryption. What it cannot do is embed a font the file does not carry, which is the
 * one thing a strict checker will still flag; the report says how many.
 */
internal fun PDDocument.makeArchival(): ArchivalReport {
    val removed = cleanUp(CleanSpec(metadata = false, scripts = true, attachments = true, comments = false))
    val catalog = documentCatalog

    // 1. The colour everything is drawn in, said once for the whole file.
    val intent = PDOutputIntent(this, ByteArrayInputStream(srgbProfile())).apply {
        info = SRGB_NAME
        outputCondition = SRGB_NAME
        outputConditionIdentifier = SRGB_NAME
        registryName = "http://www.color.org"
    }
    catalog.cosObject.removeItem(COSName.getPDFName("OutputIntents"))
    catalog.addOutputIntent(intent)

    // 2. XMP that names the standard and mirrors the document's own details. Uncompressed,
    //    as the standard asks of the metadata stream.
    val xmp = archivalXmp(this).toByteArray(Charsets.UTF_8)
    catalog.metadata = PDMetadata(this, ByteArrayInputStream(xmp))

    // 3. A file ID, which archives use to tell copies apart.
    val id = ByteArray(16).also { SecureRandom().nextBytes(it) }
    document.trailer.setItem(COSName.ID, COSArray().apply { add(COSString(id)); add(COSString(id)) })

    // 4. Every annotation printable, none hidden, as the standard requires.
    for (page in pages) {
        val annotations = runCatching { page.annotations }.getOrNull() ?: continue
        for (annotation in annotations) {
            if (annotation.subtype == "Popup") continue
            val flags = annotation.cosObject.getInt(COSName.F, 0)
            annotation.cosObject.setInt(COSName.F, (flags or 4) and (1 or 2 or 32).inv())
        }
    }

    // 5. No encryption: an archive opens for whoever keeps it.
    isAllSecurityToBeRemoved = true
    version = maxOf(version, 1.7f)

    return ArchivalReport(scripts = removed.scripts, attachments = removed.attachments, fontsMissing = countUnembeddedFonts())
}

private const val SRGB_NAME = "sRGB IEC61966-2.1"

/** What making an archive copy took out, and what it could not fix. */
data class ArchivalReport(val scripts: Int, val attachments: Int, val fontsMissing: Int)

private fun archivalXmp(doc: PDDocument): String {
    val info = doc.documentInformation
    val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    fun x(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val lines = mutableListOf<String>()
    info.title?.takeIf { it.isNotBlank() }?.let { lines += "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">${x(it)}</rdf:li></rdf:Alt></dc:title>" }
    info.author?.takeIf { it.isNotBlank() }?.let { lines += "<dc:creator><rdf:Seq><rdf:li>${x(it)}</rdf:li></rdf:Seq></dc:creator>" }
    info.subject?.takeIf { it.isNotBlank() }?.let { lines += "<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">${x(it)}</rdf:li></rdf:Alt></dc:description>" }
    info.keywords?.takeIf { it.isNotBlank() }?.let { lines += "<pdf:Keywords>${x(it)}</pdf:Keywords>" }
    info.producer?.takeIf { it.isNotBlank() }?.let { lines += "<pdf:Producer>${x(it)}</pdf:Producer>" }
    info.creator?.takeIf { it.isNotBlank() }?.let { lines += "<xmp:CreatorTool>${x(it)}</xmp:CreatorTool>" }
    info.creationDate?.let { stamp.timeZone = it.timeZone; lines += "<xmp:CreateDate>${stamp.format(it.time)}</xmp:CreateDate>" }
    info.modificationDate?.let { stamp.timeZone = it.timeZone; lines += "<xmp:ModifyDate>${stamp.format(it.time)}</xmp:ModifyDate>" }
    return listOf(
        "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>",
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">",
        "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">",
        "<rdf:Description rdf:about=\"\" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">",
        "<pdfaid:part>2</pdfaid:part>",
        "<pdfaid:conformance>B</pdfaid:conformance>"
    ).plus(lines).plus(
        listOf("</rdf:Description>", "</rdf:RDF>", "</x:xmpmeta>", "<?xpacket end=\"w\"?>")
    ).joinToString("\n")
}

/** Fonts the file uses but does not carry: the one thing an archive copy cannot fix without the font. */
private fun PDDocument.countUnembeddedFonts(): Int {
    val seen = HashSet<COSDictionary>()
    var missing = 0

    fun visitFont(font: COSDictionary?, depth: Int) {
        if (font == null || depth > 4 || !seen.add(font)) return
        val subtype = font.getNameAsString(COSName.SUBTYPE)
        if (subtype == "Type3") return
        if (subtype == "Type0") {
            val kids = font.getDictionaryObject(COSName.DESCENDANT_FONTS) as? COSArray ?: return
            for (i in 0 until kids.size()) visitFont(kids.getObject(i) as? COSDictionary, depth + 1)
            return
        }
        val descriptor = font.getDictionaryObject(COSName.FONT_DESC) as? COSDictionary
        val embedded = descriptor != null && (
            descriptor.containsKey(COSName.FONT_FILE) || descriptor.containsKey(COSName.FONT_FILE2) || descriptor.containsKey(COSName.FONT_FILE3)
            )
        if (!embedded) missing += 1
    }

    fun visitResources(resources: PDResources?, depth: Int) {
        if (resources == null || depth > 6) return
        for (name in resources.fontNames) {
            visitFont(resources.cosObject.getDictionaryObject(COSName.FONT).let { (it as? COSDictionary)?.getDictionaryObject(name) as? COSDictionary }, 0)
        }
        for (name in resources.xObjectNames) {
            val form = runCatching { resources.getXObject(name) }.getOrNull() as? com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject ?: continue
            if (seen.add(form.cosObject)) visitResources(form.resources, depth + 1)
        }
    }

    for (page in pages) visitResources(page.resources, 0)
    return missing
}

/**
 * An sRGB colour profile (ICC v2, display class), built here byte by byte rather than copied
 * from anywhere: PDF/A asks for one inside the file, and a profile made from the published
 * sRGB numbers has no licence to carry.
 *
 * Primaries are the sRGB ones adapted to D50, as ICC requires; the tone curve is the real
 * sRGB curve (linear toe, 2.4 power), sampled 1024 times.
 */
internal fun srgbProfile(): ByteArray {
    fun s15(v: Double): Int = Math.round(v * 65536).toInt()
    fun u32(n: Int): ByteArray = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    fun text(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)
    fun xyz(x: Double, y: Double, z: Double): ByteArray = text("XYZ ") + byteArrayOf(0, 0, 0, 0) + u32(s15(x)) + u32(s15(y)) + u32(s15(z))

    val parts = mutableListOf<Pair<String, ByteArray>>()
    val name = SRGB_NAME
    val desc = ByteArray(12 + name.length + 1 + 4 + 4 + 2 + 1 + 67)
    text("desc").copyInto(desc, 0)
    u32(name.length + 1).copyInto(desc, 8)
    text(name).copyInto(desc, 12)
    parts += "desc" to desc

    val notice = "No copyright, use freely"
    val cprt = ByteArray(8 + notice.length + 1)
    text("text").copyInto(cprt, 0)
    text(notice).copyInto(cprt, 8)
    parts += "cprt" to cprt

    parts += "wtpt" to xyz(0.9642, 1.0, 0.8249)
    parts += "rXYZ" to xyz(0.4361, 0.2225, 0.0139)
    parts += "gXYZ" to xyz(0.3851, 0.7169, 0.0971)
    parts += "bXYZ" to xyz(0.1431, 0.0606, 0.7141)

    val points = 1024
    val curve = ByteArray(12 + points * 2)
    text("curv").copyInto(curve, 0)
    u32(points).copyInto(curve, 8)
    for (i in 0 until points) {
        val v = i.toDouble() / (points - 1)
        val linear = if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        val n = Math.round(linear * 65535).toInt()
        curve[12 + i * 2] = (n shr 8).toByte()
        curve[13 + i * 2] = (n and 255).toByte()
    }
    // The three channels share one curve: the tag table may point them at the same bytes.
    val shared = listOf("rTRC", "gTRC", "bTRC")

    fun pad(n: Int) = (n + 3) and 3.inv()
    val tableSize = 4 + (parts.size + shared.size) * 12
    var offset = 128 + tableSize
    data class Placed(val sig: String, val at: Int, val size: Int, val data: ByteArray?)
    val placed = mutableListOf<Placed>()
    for ((sig, data) in parts) {
        placed += Placed(sig, offset, data.size, data)
        offset = pad(offset + data.size)
    }
    val curveAt = offset
    offset = pad(offset + curve.size)
    for (sig in shared) placed += Placed(sig, curveAt, curve.size, null)

    val out = ByteArray(offset)
    fun put(at: Int, bytes: ByteArray) = bytes.copyInto(out, at)
    put(0, u32(offset))
    put(8, byteArrayOf(0x02, 0x10, 0, 0)) // version 2.1
    put(12, text("mntr"))
    put(16, text("RGB "))
    put(20, text("XYZ "))
    put(24, byteArrayOf(0x07, 0xea.toByte(), 0, 1, 0, 1, 0, 0, 0, 0, 0, 0)) // 2026-01-01
    put(36, text("acsp"))
    put(68, u32(s15(0.9642)) + u32(s15(1.0)) + u32(s15(0.8249))) // D50
    put(128, u32(placed.size))
    placed.forEachIndexed { i, p ->
        put(132 + i * 12, text(p.sig))
        put(136 + i * 12, u32(p.at))
        put(140 + i * 12, u32(p.size))
        p.data?.let { put(p.at, it) }
    }
    put(curveAt, curve)
    return out
}
