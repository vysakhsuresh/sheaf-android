package com.layerbit.sheaf.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.BuildConfig
import com.layerbit.sheaf.SheafApplication
import com.layerbit.sheaf.brand.BrandLinks
import com.layerbit.sheaf.ui.components.Panel
import com.layerbit.sheaf.ui.components.RowBetween
import com.layerbit.sheaf.ui.components.SectionHeading
import com.layerbit.sheaf.ui.components.formatBytes
import com.layerbit.sheaf.ui.theme.SheafColors

/**
 * What the app is, what it refuses to do, and how to reach a person.
 *
 * Reachable from the top bar on the home screen. It used to sit at the bottom of the tool
 * grid, which stopped being reachable the moment the grid grew past a screen.
 */
@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sheaf = context.applicationContext as SheafApplication

    // Read once per visit rather than observed. It only changes when a job runs or the button
    // below is pressed, and neither happens while this screen is open.
    var workingBytes by remember { mutableStateOf(sheaf.workspace.sizeBytes()) }
    var cleared by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = SheafColors.Dim
            )
        }

        item {
            Panel(modifier = Modifier.fillMaxWidth()) {
                SectionHeading("Nothing leaves this phone")
                Text(
                    text = "Sheaf does not have permission to use the internet. Not restricted, " +
                        "not switched off in settings - the permission is absent from the app, " +
                        "so the process cannot open a network connection at all.\n\n" +
                        "Your documents are read and written on this device and nowhere else. " +
                        "You can check this yourself: the Play Store listing shows every " +
                        "permission an app declares, and this one declares no network access.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SheafColors.Muted,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        item {
            Panel(modifier = Modifier.fillMaxWidth()) {
                SectionHeading("What that costs")
                Text(
                    text = "There is no crash reporting, because sending a crash report would " +
                        "need the internet. If something goes wrong, the fastest fix is to " +
                        "tell us what you were doing when it happened.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SheafColors.Muted,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = {
                    BrandLinks.sendEmail(
                        context,
                        subject = "Sheaf ${BuildConfig.VERSION_NAME} - feedback",
                        body = ""
                    )
                }) { Text("Email us", color = SheafColors.Band) }
                TextButton(onClick = { BrandLinks.openUrl(context, BrandLinks.WHATSAPP_URL) }) {
                    Text("Message us on WhatsApp", color = SheafColors.Band)
                }
                Text(
                    text = "Opening either hands the address to another app, which does the " +
                        "sending under its own permissions. Sheaf still cannot open a socket, " +
                        "and nothing about your documents travels with it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        item {
            Panel(modifier = Modifier.fillMaxWidth()) {
                SectionHeading("Working files")
                Text(
                    text = "Sheaf copies a document before working on it, so your original is " +
                        "never touched. The copies and the results live in the app's own " +
                        "storage until you clear them, and they are not backed up anywhere.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SheafColors.Muted,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Spacer(Modifier.height(8.dp))
                RowBetween {
                    Text(
                        text = if (cleared) "Cleared" else formatBytes(workingBytes),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (cleared) SheafColors.Done else SheafColors.Text
                    )
                    TextButton(
                        onClick = {
                            sheaf.workspace.clear()
                            sheaf.documentStore.clearImports()
                            workingBytes = 0
                            cleared = true
                        },
                        enabled = workingBytes > 0 && !cleared
                    ) {
                        Text(
                            "Clear now",
                            color = if (workingBytes > 0 && !cleared) SheafColors.Band else SheafColors.Dim
                        )
                    }
                }
                Text(
                    text = "Anything you have already saved or shared is kept - this only " +
                        "clears Sheaf's own scratch space.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        item {
            Panel(modifier = Modifier.fillMaxWidth()) {
                SectionHeading("Free, and staying that way")
                Text(
                    text = "Every tool in Sheaf is free. There are no ads, no subscription and " +
                        "no account, and there is no server behind it to pay for. If it has " +
                        "saved you something, a coffee is welcome and entirely optional.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SheafColors.Muted,
                    modifier = Modifier.padding(top = 8.dp)
                )
                TextButton(
                    onClick = { BrandLinks.openUrl(context, BrandLinks.COFFEE_URL) },
                    modifier = Modifier.padding(top = 4.dp)
                ) { Text("Buy us a coffee", color = SheafColors.Band) }
            }
        }

        item {
            Column {
                TextButton(onClick = { BrandLinks.openUrl(context, BrandLinks.WEBSITE_URL) }) {
                    Text("From ${BrandLinks.BRAND_LABEL}", color = SheafColors.Muted)
                }
                TextButton(onClick = { BrandLinks.openUrl(context, BrandLinks.PLAY_LISTING) }) {
                    Text("Rate Sheaf on Google Play", color = SheafColors.Dim)
                }
            }
        }
    }
}
