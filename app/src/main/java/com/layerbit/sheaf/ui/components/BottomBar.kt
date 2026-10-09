package com.layerbit.sheaf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.shape.RoundedCornerShape
import com.layerbit.sheaf.ui.theme.SheafColors
import com.layerbit.sheaf.ui.theme.brandSweep

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
    Column(modifier = modifier.fillMaxWidth()) {
        // A hairline, not a shadow. The bar is a lift of a few per cent against the content
        // behind it, which is enough to read as a different plane but not enough to find an
        // edge in a hurry; one line does that and costs a pixel.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(SheafColors.Border)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SheafColors.SurfaceTint)
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
                    // The selected tab is painted, not merely tinted.
                    //
                    // It used to be a magenta glyph beside two grey ones, which is a 20dp
                    // difference in a 56dp row: on the shelf of the three places the app
                    // keeps things, you had to look to see where you were. A filled pill in
                    // the brand sweep is the same information at arm's length, and it is the
                    // one place in the chrome the band is spent.
                    Box(
                        modifier = Modifier
                            .size(width = 44.dp, height = 26.dp)
                            .then(
                                if (selected) {
                                    Modifier.background(brandSweep(), RoundedCornerShape(999.dp))
                                } else {
                                    Modifier
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(destination.icon),
                            contentDescription = null,
                            tint = if (selected) SheafColors.OnBand else SheafColors.Dim,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = destination.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) SheafColors.BandBright else SheafColors.Dim,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
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
