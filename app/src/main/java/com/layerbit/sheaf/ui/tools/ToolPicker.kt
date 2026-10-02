package com.layerbit.sheaf.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.ui.components.SectionHeading
import com.layerbit.sheaf.ui.theme.SheafColors

/**
 * Every tool that can take a document that is already in hand.
 *
 * One sheet, used twice: by the viewer for the document on screen, and by a finished result
 * for the file a tool just produced. The second is what makes a chain possible - merge, then
 * compress, then add a password, without saving to Files and picking the same thing back up
 * between each step.
 *
 * Scan and Images to PDF are left out wherever this is shown, because both make a document
 * rather than take one; offering them here would be offering something that cannot accept
 * what is being handed over.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolPickerSheet(
    heading: String,
    documentName: String,
    onDismiss: () -> Unit,
    onPick: (ToolId) -> Unit,
    exclude: Set<ToolId> = emptySet()
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheafColors.Surface
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            SectionHeading(heading)
            Text(
                text = documentName,
                style = MaterialTheme.typography.titleMedium,
                color = SheafColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
            )
            ToolId.entries
                .filter { it != ToolId.IMAGES_TO_PDF && it != ToolId.SCAN && it !in exclude }
                .forEach { tool ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(tool) }
                            .padding(vertical = 12.dp)
                    ) {
                        Icon(
                            painter = painterResource(iconFor(tool)),
                            contentDescription = null,
                            tint = SheafColors.Muted,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.padding(start = 14.dp)) {
                            Text(
                                tool.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = SheafColors.Text
                            )
                            Text(
                                tool.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = SheafColors.Dim
                            )
                        }
                    }
                }
        }
    }
}
