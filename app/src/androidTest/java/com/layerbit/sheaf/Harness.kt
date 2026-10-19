package com.layerbit.sheaf

import androidx.test.platform.app.InstrumentationRegistry
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.ops.Op
import com.layerbit.sheaf.ops.OpContext
import com.layerbit.sheaf.ops.OpOutcome
import kotlinx.coroutines.runBlocking
import java.io.File

/** Runs ops the way the worker does, on the phone, with the app's own engines. */
object Harness {
    val app: SheafApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SheafApplication

    val dir: File get() = File(app.cacheDir, "op-tests").apply { mkdirs() }

    fun context() = OpContext(app.workspace, app.pdfEngine, app.encryptedReader, app.surgeon, app.ocr)

    fun run(op: Op, vararg inputs: SheafFile): List<OpOutcome> = runBlocking { op.run(inputs.toList(), context()) { } }

    fun pdf(file: File, name: String = file.name) = SheafFile(file, name, SheafFile.Origin.Derived("test"))
    fun image(file: File, mime: String = "image/jpeg") = SheafFile(file, file.name, SheafFile.Origin.Derived("test"), mime)
}
