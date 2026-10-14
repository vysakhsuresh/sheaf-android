package com.layerbit.sheaf.pdf

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceCharacteristicsDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField

/**
 * Puts fillable fields on pages: text boxes, paragraph boxes, tick boxes and drop-down
 * lists, where somebody dragged them. The result is a real PDF form that any reader fills.
 *
 * Each field gets a widget on its page with a pale blue box and a grey rule, so the person
 * filling it in can see where to type. A tick box needs its two looks - empty and ticked -
 * drawn here, because PDFBox draws a text field's look for itself but not a tick box's.
 */
internal fun PDDocument.addFormFields(fields: List<NewField>): List<String> {
    val catalog = documentCatalog
    val form = catalog.acroForm ?: PDAcroForm(this).also { catalog.acroForm = it }
    val resources = form.defaultResources ?: PDResources().also { form.defaultResources = it }
    resources.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA)
    resources.put(COSName.getPDFName("ZaDb"), PDType1Font.ZAPF_DINGBATS)
    form.defaultAppearance = "/Helv 0 Tf 0 g"
    form.isNeedAppearances = false

    val taken = form.fields.mapNotNull { it.fullyQualifiedName }.toMutableSet()
    val names = mutableListOf<String>()

    for (field in fields) {
        if (field.pageIndex !in 0 until numberOfPages) continue
        val page = getPage(field.pageIndex)
        val shown = ShownPage(page)
        val rect = shown.storedRect(
            field.area.left,
            field.area.top,
            field.area.left + field.area.width,
            field.area.top + field.area.height
        )
        // A dot in a name makes a hierarchy in a PDF form; a name is just a name here.
        val base = field.name.replace('.', ' ').replace(Regex("\\s+"), " ").trim().take(120).ifEmpty { "Field" }
        var name = base
        var n = 2
        while (name in taken) { name = "$base $n"; n += 1 }
        taken += name

        val look = PDAppearanceCharacteristicsDictionary(COSDictionary()).apply {
            borderColour = PDColor(floatArrayOf(0.55f, 0.58f, 0.65f), PDDeviceRGB.INSTANCE)
            background = PDColor(floatArrayOf(0.94f, 0.96f, 1f), PDDeviceRGB.INSTANCE)
            rotation = shown.rotation
        }

        when (field.kind) {
            NewField.Kind.TEXT, NewField.Kind.MULTILINE -> {
                val text = PDTextField(form)
                text.partialName = name
                text.defaultAppearance = "/Helv 0 Tf 0 g"
                text.isMultiline = field.kind == NewField.Kind.MULTILINE
                form.fields.add(text)
                val widget = text.widgets.first()
                place(widget, page, rect, look)
                // Setting a value, even an empty one, is what makes PDFBox draw the box.
                runCatching { text.value = "" }
            }
            NewField.Kind.CHECK -> {
                val box = PDCheckBox(form)
                box.partialName = name
                form.fields.add(box)
                val widget = box.widgets.first()
                place(widget, page, rect, look)
                widget.appearance = checkBoxLooks(this, rect)
                widget.setAppearanceState("Off")
                box.cosObject.setItem(COSName.V, COSName.OFF)
            }
            NewField.Kind.CHOICE -> {
                val choice = PDComboBox(form)
                choice.partialName = name
                choice.defaultAppearance = "/Helv 0 Tf 0 g"
                val options = field.options.map { it.trim() }.filter { it.isNotEmpty() }
                if (options.isNotEmpty()) choice.options = options
                form.fields.add(choice)
                val widget = choice.widgets.first()
                place(widget, page, rect, look)
                runCatching { if (options.isNotEmpty()) choice.value = options.first() }
            }
        }
        names += name
    }
    return names
}

private fun place(widget: PDAnnotationWidget, page: PDPage, rect: PDRectangle, look: PDAppearanceCharacteristicsDictionary) {
    widget.rectangle = rect
    widget.page = page
    widget.appearanceCharacteristics = look
    widget.isPrinted = true
    val annotations = page.annotations
    annotations.add(widget)
    page.annotations = annotations
}

/** The two looks of a tick box: an empty square, and the same square with a tick in it. */
private fun checkBoxLooks(doc: PDDocument, rect: PDRectangle): PDAppearanceDictionary {
    val width = rect.width
    val height = rect.height
    fun look(ticked: Boolean): PDAppearanceStream {
        val stream = PDAppearanceStream(doc)
        stream.resources = PDResources().also { it.put(COSName.getPDFName("ZaDb"), PDType1Font.ZAPF_DINGBATS) }
        stream.bBox = PDRectangle(0f, 0f, width, height)
        PDPageContentStream(doc, stream).use { s ->
            s.setNonStrokingColor(0.94f, 0.96f, 1f)
            s.setStrokingColor(0.55f, 0.58f, 0.65f)
            s.setLineWidth(0.75f)
            s.addRect(0.375f, 0.375f, width - 0.75f, height - 0.75f)
            s.fillAndStroke()
            if (ticked) {
                // The tick is ZapfDingbats' "4", the glyph every reader uses for one.
                val size = minOf(width, height) * 0.8f
                val glyphWidth = PDType1Font.ZAPF_DINGBATS.getStringWidth("4") / 1000f * size
                s.setNonStrokingColor(0f, 0f, 0f)
                s.beginText()
                s.setFont(PDType1Font.ZAPF_DINGBATS, size)
                s.newLineAtOffset((width - glyphWidth) / 2f, (height - size * 0.7f) / 2f)
                s.showText("4")
                s.endText()
            }
        }
        return stream
    }
    val states = COSDictionary()
    states.setItem(COSName.getPDFName("Yes"), look(true))
    states.setItem(COSName.OFF, look(false))
    val appearance = PDAppearanceDictionary()
    appearance.cosObject.setItem(COSName.N, states)
    return appearance
}
