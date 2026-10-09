package com.layerbit.sheaf.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.ui.components.AccentTile
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
 *
 * The rows scroll and the heading does not. ModalBottomSheet adds no scrolling of its own, so
 * for a long while this sheet simply drew two dozen rows into whatever height it had and
 * clipped the rest off the bottom with no way to reach them. Keeping the heading and the
 * document name outside the scrolling column also keeps the answer to "what is this sheet
 * about to do, and to what" on screen while you hunt down it.
 *
 * It is grouped and starred for the same reason the home screen is: Tools.kt says a flat list
 * of a dozen tools stops being a menu and becomes a wall, and this sheet is that same wall
 * with less room. A heading is also how you can tell there is more below it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolPickerSheet(
    heading: String,
    documentName: String,
    onDismiss: () -> Unit,
    onPick: (ToolId) -> Unit,
    exclude: Set<ToolId> = emptySet(),
    favourites: Set<String> = emptySet()
) {
    val sheetState = rememberModalBottomSheetState()
    val offered = remember(exclude) {
        ToolId.entries.filter { it != ToolId.IMAGES_TO_PDF && it != ToolId.SCAN && it !in exclude }
    }
    val starred = remember(offered, favourites) { offered.filter { it.name in favourites } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheafColors.Surface
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            SectionHeading(heading)
            Text(
                text = documentName,
                style = MaterialTheme.typography.titleMedium,
                color = SheafColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
            )
        }
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            if (starred.isNotEmpty()) {
                ToolGroup("Yours", starred, onPick)
            }
            ToolId.Group.entries.forEach { group ->
                val inGroup = offered.filter { it.group == group }
                if (inGroup.isNotEmpty()) {
                    ToolGroup(group.title, inGroup, onPick)
                }
            }
        }
    }
}

/**
 * A heading and the rows under it. Emitted into the caller's column rather than wrapping them
 * in one of its own, so the rows all share a single scrolling parent.
 */
@Composable
private fun ToolGroup(title: String, tools: List<ToolId>, onPick: (ToolId) -> Unit) {
    SectionHeading(
        title,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
        color = tools.firstOrNull()?.let { SheafColors.ToolAccents[it.group.ordinal].ink }
            ?: SheafColors.Dim
    )
    tools.forEach { tool ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                // Clipped before the gesture, so the press washes a rounded shape rather than
                // a full-width rectangle across the sheet.
                .clip(MaterialTheme.shapes.small)
                .clickable { onPick(tool) }
                .padding(vertical = 12.dp, horizontal = 4.dp)
        ) {
            // The same hue the tool wears on the board, so moving between the two screens is
            // recognising a colour rather than re-reading a list.
            AccentTile(
                icon = iconFor(tool),
                accent = SheafColors.ToolAccents[tool.group.ordinal],
                size = 28.dp
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
