package com.layerbit.sheaf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.ui.theme.SheafColors

/**
 * The three places Sheaf keeps things: the board, work in progress, and work finished.
 *
 * WHERE THIS DOES AND DOES NOT APPEAR. Top-level destinations only. It is deliberately absent
 * from the viewer and from a tool that is being set up, and that is not an oversight to be
 * tidied up later.
 *
 * A bar is about 80dp. On a reading surface that is 80dp of document, on a screen whose whole
 * job is to show a page as large as it will go - and the home screen was already reworked
 * once to win back less than that. Inside a tool it is worse than a cost: a half-configured
 * operation is exactly the state where a row of tempting elsewheres does the user no favours,
 * and both of those screens have a back arrow that says precisely where it goes.
 *
 * So the rule is: somewhere you dwell gets the bar, somewhere you are doing one thing does
 * not. Three destinations rather than four - Recently deleted is reached from the Library,
 * the way every operating system puts a bin behind the thing it is the bin for, because a tab
 * that is empty most of the time teaches people to ignore that row.
 */
@Composable
fun SheafBottomBar(
    destinations: List<BottomDestination>,
    currentRoute: String?,
    onSelect: (BottomDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SheafColors.Surface)
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        destinations.forEach { destination ->
            val selected = currentRoute == destination.route
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 56.dp)
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onSelect(destination) }
                    )
                    .padding(vertical = 8.dp)
            ) {
                Icon(
                    painter = painterResource(destination.icon),
                    contentDescription = null,
                    tint = if (selected) SheafColors.BandBright else SheafColors.Dim,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = destination.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) SheafColors.BandBright else SheafColors.Dim,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/**
 * One tab.
 *
 * The label is not optional and the icon carries no description of its own: the two are read
 * together as one control, so describing both makes a screen reader say everything twice.
 */
data class BottomDestination(
    val route: String,
    val label: String,
    val icon: Int
)
