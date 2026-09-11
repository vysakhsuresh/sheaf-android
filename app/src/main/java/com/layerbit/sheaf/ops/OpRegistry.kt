package com.layerbit.sheaf.ops

/**
 * Every operation the app can run, in one list.
 *
 * Adding a tool should cost one class and one line here. If it ever costs more than that, the
 * seam is wrong and the fix is to widen [Op] or [com.layerbit.sheaf.pdf.PdfEngine] rather than
 * to special-case the new tool in the UI.
 *
 * P0 registers nothing: the viewer reads documents directly through the engine and no
 * operation exists yet to transform one. The eight core tools land in P1, which is when this
 * list earns its keep.
 */
object OpRegistry {

    private val ops = mutableMapOf<String, Op>()

    fun register(op: Op) {
        require(ops.put(op.id, op) == null) {
            // A duplicate id would silently shadow a tool in saved presets, which are stored
            // by id. Better to fail at startup than to have a preset quietly change meaning.
            "Two operations share the id '${op.id}'"
        }
    }

    fun byId(id: String): Op? = ops[id]

    fun all(): List<Op> = ops.values.sortedBy { it.title }
}
