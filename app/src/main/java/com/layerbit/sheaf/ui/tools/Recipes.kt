package com.layerbit.sheaf.ui.tools

import com.layerbit.sheaf.text.Strings
import com.layerbit.sheaf.R
import com.layerbit.sheaf.ops.ToolId
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Modifier

/** One step of a recipe: a tool and the options it runs with. */
data class RecipeStep(val tool: ToolId, val config: ToolConfig)

/** Steps done often, saved once, run on any number of files in one tap. */
data class Recipe(val id: String, val name: String, val steps: List<RecipeStep>) {
    /** Locking with a password needs the password typed each time; it is never kept. */
    val needsPassword: Boolean get() = steps.any { it.tool == ToolId.SET_PASSWORD && !it.config.restrictOnly }

    val summary: String get() = steps.joinToString(" → ") { it.tool.title }
}

object Recipes {

    /** The tools a recipe can use: ones that work on whole files, any number of them. */
    val TOOLS = listOf(
        ToolId.MERGE, ToolId.COMPRESS, ToolId.OCR, ToolId.PAGE_NUMBERS, ToolId.HEADER_FOOTER,
        ToolId.WATERMARK, ToolId.BACKGROUND, ToolId.CROP, ToolId.N_UP, ToolId.PDF_A,
        ToolId.SET_PASSWORD, ToolId.PDF_TO_OFFICE, ToolId.EXTRACT_TEXT
    )

    /** Their results are not PDFs, so nothing can come after them. */
    val ENDS_A_RECIPE = setOf(ToolId.PDF_TO_OFFICE, ToolId.EXTRACT_TEXT)

    fun problemWith(name: String, steps: List<RecipeStep>): String? = when {
        name.isBlank() -> Strings.get(R.string.tools_recipe_no_name)
        steps.isEmpty() -> Strings.get(R.string.tools_recipe_no_steps)
        steps.map { it.tool }.distinct().size != steps.size -> Strings.get(R.string.tools_recipe_tool_twice)
        steps.dropLast(1).any { it.tool in ENDS_A_RECIPE } ->
            Strings.get(R.string.tools_recipe_convert_last)
        else -> null
    }

    // ---- kept on the phone, as JSON in the app's own files ----

    /** Never kept: passwords, and anything typed for one particular document. */
    private val NOT_KEPT = setOf("newPassword", "confirmPassword", "certPassword")

    fun read(file: File): List<Recipe> = runCatching {
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val stepsJson = o.optJSONArray("steps") ?: return@mapNotNull null
            val steps = (0 until stepsJson.length()).mapNotNull { k ->
                val s = stepsJson.optJSONObject(k) ?: return@mapNotNull null
                val tool = ToolId.entries.firstOrNull { it.name == s.optString("tool") }?.takeIf { it in TOOLS } ?: return@mapNotNull null
                RecipeStep(tool, configFrom(s.optJSONObject("options") ?: JSONObject(), tool))
            }
            val name = o.optString("name").trim().take(80)
            if (problemWith(name, steps) != null) return@mapNotNull null
            Recipe(o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() }, name, steps)
        }
    }.getOrDefault(emptyList())

    fun write(file: File, recipes: List<Recipe>): Boolean = runCatching {
        val array = JSONArray()
        for (r in recipes) {
            array.put(JSONObject().apply {
                put("id", r.id)
                put("name", r.name)
                put("steps", JSONArray().apply {
                    r.steps.forEach { s -> put(JSONObject().put("tool", s.tool.name).put("options", optionsOf(s.config, s.tool))) }
                })
            })
        }
        val temp = File(file.parentFile, file.name + ".new")
        temp.writeText(array.toString())
        temp.renameTo(file) || run { file.writeText(array.toString()); true }
    }.getOrDefault(false)

    private val fields by lazy {
        ToolConfig::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) && it.name !in NOT_KEPT }
            .onEach { it.isAccessible = true }
    }

    /** Only what differs from the tool's own starting options, so a new option added later starts right. */
    fun optionsOf(config: ToolConfig, tool: ToolId): JSONObject {
        val base = ToolConfig.defaultFor(tool)
        val out = JSONObject()
        for (f in fields) {
            val value = f.get(config)
            if (value == f.get(base)) continue
            out.put(f.name, when (value) {
                null -> JSONObject.NULL
                is Enum<*> -> value.name
                is Float -> value.toDouble()
                else -> value
            })
        }
        return out
    }

    fun configFrom(options: JSONObject, tool: ToolId): ToolConfig {
        val config = ToolConfig.defaultFor(tool)
        for (f in fields) {
            if (!options.has(f.name)) continue
            val raw = options.get(f.name)
            val value: Any? = runCatching {
                when {
                    raw == JSONObject.NULL -> null
                    f.type.isEnum -> f.type.enumConstants?.firstOrNull { (it as Enum<*>).name == raw.toString() }
                    f.type == Int::class.javaPrimitiveType -> (raw as Number).toInt()
                    f.type == Long::class.javaPrimitiveType -> (raw as Number).toLong()
                    f.type == Float::class.javaPrimitiveType -> (raw as Number).toFloat()
                    f.type == Boolean::class.javaPrimitiveType -> raw as Boolean
                    f.type == String::class.java -> raw.toString()
                    else -> return@runCatching Unit
                }
            }.getOrDefault(Unit)
            if (value == Unit) continue
            if (value == null && f.type.isPrimitive) continue
            runCatching { f.set(config, value) }
        }
        return config
    }
}
