package com.layerbit.sheaf.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.BuildConfig
import com.layerbit.sheaf.R
import com.layerbit.sheaf.SheafApplication
import com.layerbit.sheaf.brand.BrandLinks
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.ui.components.IconTile
import com.layerbit.sheaf.ui.components.Panel
import com.layerbit.sheaf.ui.components.Pill
import com.layerbit.sheaf.ui.components.PrimaryButton
import com.layerbit.sheaf.ui.components.RowBetween
import com.layerbit.sheaf.ui.components.SecondaryButton
import com.layerbit.sheaf.ui.components.formatBytes
import com.layerbit.sheaf.ui.theme.SheafColors

/**
 * What the app is, what it refuses to do, and how to reach a person.
 *
 * It opens with the mark and the three facts that matter - no internet, no ads, no account -
 * and then says each of them properly, because a claim about privacy is worth nothing until
 * it says how it can be checked.
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
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Masthead() }

        item {
            Panel(modifier = Modifier.fillMaxWidth(), tint = SheafColors.Done) {
                Row(verticalAlignment = Alignment.Top) {
                    IconTile(iconRes = R.drawable.ic_tool_lock, accent = SheafColors.Done, filled = true)
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text("Nothing leaves this phone", style = MaterialTheme.typography.titleMedium, color = SheafColors.Text)
                        Text(
                            text = "Sheaf does not have permission to use the internet. Not restricted, " +
                                "not switched off in settings - the permission is absent from the app, " +
                                "so the process cannot open a network connection at all.\n\n" +
                                "Your documents are read and written on this device and nowhere else. " +
                                "You can check this yourself: the Play Store listing shows every " +
                                "permission an app declares, and this one declares no network access.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SheafColors.Muted,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }

        item {
            Panel(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Top) {
                    IconTile(iconRes = R.drawable.ic_tool_add_text, accent = Color(0xFF5B9DFF))
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text("Tell us what went wrong", style = MaterialTheme.typography.titleMedium, color = SheafColors.Text)
                        Text(
                            text = "There is no crash reporting, because sending a crash report would " +
                                "need the internet. If something goes wrong, the fastest fix is to " +
                                "tell us what you were doing when it happened.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SheafColors.Muted,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    PrimaryButton(
                        text = "Email us",
                        icon = Icons.Filled.Email,
                        onClick = {
                            BrandLinks.sendEmail(
                                context,
                                subject = "Sheaf ${BuildConfig.VERSION_NAME} - feedback",
                                body = ""
                            )
                        },
                        modifier = Modifier.weight(1f),
                        tall = false
                    )
                    SecondaryButton(
                        text = "WhatsApp",
                        icon = Icons.Filled.Send,
                        onClick = { BrandLinks.openUrl(context, BrandLinks.WHATSAPP_URL) },
                        modifier = Modifier.weight(1f),
                        tall = false
                    )
                }
                Text(
                    text = "Opening either hands the address to another app, which does the " +
                        "sending under its own permissions. Sheaf still cannot open a socket, " +
                        "and nothing about your documents travels with it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }

        item {
            Panel(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Top) {
                    IconTile(iconRes = R.drawable.ic_tool_split_size, accent = Color(0xFF3ED3C0))
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text("Working files", style = MaterialTheme.typography.titleMedium, color = SheafColors.Text)
                        Text(
                            text = "Sheaf copies a document before working on it, so your original is " +
                                "never touched. The copies and the results live in the app's own " +
                                "storage until you clear them, and they are not backed up anywhere.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SheafColors.Muted,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                RowBetween(modifier = Modifier.padding(top = 14.dp)) {
                    Text(
                        text = if (cleared) "Cleared" else formatBytes(workingBytes),
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (cleared) SheafColors.Done else SheafColors.Text
                    )
                    SecondaryButton(
                        text = "Clear now",
                        onClick = {
                            sheaf.workspace.clear()
                            sheaf.documentStore.clearImports()
                            workingBytes = 0
                            cleared = true
                        },
                        enabled = workingBytes > 0 && !cleared,
                        tall = false
                    )
                }
                Text(
                    text = "Anything you have already saved or shared is kept - this only " +
                        "clears Sheaf's own scratch space.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        item {
            Panel(modifier = Modifier.fillMaxWidth(), tint = SheafColors.Band) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier.size(40.dp).background(SheafColors.Band.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(Icons.Filled.Favorite, contentDescription = null, tint = SheafColors.BandBright, modifier = Modifier.size(20.dp))
                    }
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text("Free, and staying that way", style = MaterialTheme.typography.titleMedium, color = SheafColors.Text)
                        Text(
                            text = "Every tool in Sheaf is free. There are no ads, no subscription and " +
                                "no account, and there is no server behind it to pay for. If it has " +
                                "saved you something, a coffee is welcome and entirely optional.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SheafColors.Muted,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                PrimaryButton(
                    text = "Buy us a coffee",
                    onClick = { BrandLinks.openUrl(context, BrandLinks.COFFEE_URL) },
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    tall = false
                )
            }
        }

        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                SecondaryButton(
                    text = "Rate Sheaf on Google Play",
                    icon = Icons.Filled.Star,
                    onClick = { BrandLinks.openUrl(context, BrandLinks.PLAY_LISTING) },
                    modifier = Modifier.fillMaxWidth(),
                    tall = false
                )
                TextButton(onClick = { BrandLinks.openUrl(context, BrandLinks.WEBSITE_URL) }) {
                    Text("Made by ${BrandLinks.BRAND_LABEL} · ${BrandLinks.WEBSITE_URL.removePrefix("https://")}", color = SheafColors.Dim, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** The mark, the name, the version, and the three facts in pills. */
@Composable
private fun Masthead() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    listOf(SheafColors.Band.copy(alpha = if (SheafColors.isDark) 0.2f else 0.12f), Color.Transparent)
                )
            )
            .padding(vertical = 22.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF0B1220)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = "Sheaf",
                modifier = Modifier.size(132.dp)
            )
        }
        Text("Sheaf", style = MaterialTheme.typography.headlineMedium, color = SheafColors.Text, modifier = Modifier.padding(top = 12.dp))
        Text(
            "Every PDF tool. Nothing leaves your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = SheafColors.Muted,
            textAlign = TextAlign.Center
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 12.dp)) {
            Pill("Version ${BuildConfig.VERSION_NAME}", SheafColors.Muted)
            Pill("${ToolId.entries.size} tools", SheafColors.Band)
            Pill("No internet", SheafColors.Done)
        }
        Spacer(Modifier.height(2.dp))
    }
}
