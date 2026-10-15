package com.layerbit.sheaf.pdf

/**
 * The one shape every converter speaks.
 *
 * PDF to Word, Excel, EPUB and HTML build one of these from what the engine reads off the
 * pages; Word, Excel and text to PDF read their file into one and the engine lays it out.
 * The writers and readers never meet a PDF library, and the PDF side never meets OOXML.
 *
 * Units are PDF points (1/72 inch) throughout.
 */
data class FlowDocument(
    val title: String,
    val blocks: List<Block>
)

sealed interface Block {

    /** A run of text in one style. */
    data class Run(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        /** Font size in points. Zero means the paragraph's own. */
        val size: Float = 0f
    )

    data class Paragraph(
        val runs: List<Run>,
        /** 1 to 3 for a heading; zero for body text. */
        val heading: Int = 0,
        val align: Align = Align.LEFT,
        /** A bulleted or numbered list item, and how deep. Null when not a list. */
        val list: ListMark? = null,
        /** Body size in points. */
        val size: Float = 11f
    ) : Block {
        val text: String get() = runs.joinToString("") { it.text }
    }

    enum class Align { LEFT, CENTRE, RIGHT, JUSTIFY }

    data class ListMark(val ordered: Boolean, val level: Int = 0, val number: Int = 1)

    /** Rows of cells, each cell a few lines of text. */
    data class Table(
        val rows: List<List<String>>,
        /** Rows at the top that are headings. */
        val headerRows: Int = 0
    ) : Block {
        val columns: Int get() = rows.maxOfOrNull { it.size } ?: 0
    }

    /** A picture, as PNG or JPEG bytes, shown at the size given. */
    data class Picture(
        val bytes: ByteArray,
        val jpeg: Boolean,
        val widthPoints: Float,
        val heightPoints: Float
    ) : Block

    data object PageBreak : Block
}

/**
 * A line of text as it sits on a page, for turning pages back into paragraphs.
 *
 * [cells] is the line cut at its wide gaps - two or more spaces' worth - which is how a
 * table on a page reveals its columns. [text] joins them with a space.
 */
data class TextLine(
    val cells: List<String>,
    val sizePoints: Float,
    val bold: Boolean,
    val italic: Boolean,
    /** Fractions of the page the reader sees, origin top-left. */
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
) {
    val text: String get() = cells.joinToString(" ")
}
