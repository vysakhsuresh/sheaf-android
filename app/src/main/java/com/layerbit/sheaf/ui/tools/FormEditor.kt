package com.layerbit.sheaf.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.pdf.FormField
import com.layerbit.sheaf.ui.components.ChipRow
import com.layerbit.sheaf.ui.components.Panel
import com.layerbit.sheaf.ui.components.SectionHeading
import com.layerbit.sheaf.ui.components.ToggleRow
import com.layerbit.sheaf.ui.theme.SheafColors

/**
 * The fillable fields of a form, as the controls they actually are.
 *
 * A text field gets a box, a tick box gets a switch, and a chooser gets its own options as
 * chips - rather than one text box per field and a hope that the user types one of the values
 * the field will accept. A radio group that rejects anything but "Yes" or "No" is exactly the
 * case where a free text box wastes somebody's afternoon.
 *
 * Field names in a real form are not written for people - a government form will call a box
 * `topmostSubform[0].Page1[0].f1_01[0]` and label it "Your first name". The label is used when
 * the form's author wrote one, and the tail of the name when they did not, because the whole
 * name is unreadable and the tail is usually the part that means something.
 */
@Composable
fun FormEditor(
    fields: List<FormField>,
    values: Map<String, String>,
    onValue: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeading(if (fields.size == 1) "1 field" else "${fields.size} fields")

        fields.forEach { field ->
            val value = values[field.name] ?: field.value
            val label = field.label?.takeIf { it.isNotBlank() }
                ?: field.name.substringAfterLast('.').ifBlank { field.name }

            when {
                field.readOnly -> Panel(modifier = Modifier.fillMaxWidth()) {
                    SectionHeading(label)
                    Text(
                        text = value.ifBlank { "Empty" },
                        style = MaterialTheme.typography.bodyLarge,
                        color = SheafColors.Dim,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Text(
                        "The form marks this one as not editable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SheafColors.Dim
                    )
                }

                field.kind == FormField.Kind.TICK -> ToggleRow(
                    title = label,
                    checked = value.isNotBlank() && value != "false",
                    onChange = { on -> onValue(field.name, if (on) "true" else "") }
                )

                field.kind == FormField.Kind.CHOICE && field.options.isNotEmpty() -> Column {
                    SectionHeading(label)
                    Spacer(Modifier.height(6.dp))
                    ChipRow(
                        options = field.options.map { it to it },
                        selected = value,
                        onSelect = { chosen -> onValue(field.name, chosen) }
                    )
                }

                else -> Field(
                    value = value,
                    label = label,
                    keyboardType = KeyboardType.Text,
                    onChange = { typed -> onValue(field.name, typed) }
                )
            }
        }
    }
}

/** Shown when the document turns out not to be a form at all, which is the common case. */
@Composable
fun NoFormFields(modifier: Modifier = Modifier) {
    Panel(modifier = modifier.fillMaxWidth()) {
        SectionHeading("No form fields")
        Text(
            "This document has no fillable fields in it. That is normal - a PDF only has " +
                "them if it was built as a form, and a scan or a printout of a form is a " +
                "picture of one.",
            style = MaterialTheme.typography.bodyMedium,
            color = SheafColors.Muted,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            "Use Add text instead: tap where the answer goes and type it.",
            style = MaterialTheme.typography.bodyMedium,
            color = SheafColors.BandBright,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
