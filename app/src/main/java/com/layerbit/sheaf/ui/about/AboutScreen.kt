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
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
                        Text(stringResource(R.string.app_about_offline_title), style = MaterialTheme.typography.titleMedium, color = SheafColors.Text)
                        Text(
                            text = stringResource(R.string.app_about_offline_body),
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
                        Text(stringResource(R.string.app_about_feedback_title), style = MaterialTheme.typography.titleMedium, color = SheafColors.Text)
                        Text(
                            text = stringResource(R.string.app_about_feedback_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = SheafColors.Muted,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    PrimaryButton(
                        text = stringResource(R.string.app_about_email),
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
                    text = stringResource(R.string.app_about_feedback_note),
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
                        Text(stringResource(R.string.app_about_working_title), style = MaterialTheme.typography.titleMedium, color = SheafColors.Text)
                        Text(
                            text = stringResource(R.string.app_about_working_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = SheafColors.Muted,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                RowBetween(modifier = Modifier.padding(top = 14.dp)) {
                    Text(
                        text = if (cleared) stringResource(R.string.app_about_cleared) else formatBytes(workingBytes),
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (cleared) SheafColors.Done else SheafColors.Text
                    )
                    SecondaryButton(
                        text = stringResource(R.string.app_about_clear_now),
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
                    text = stringResource(R.string.app_about_working_note),
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
                        Text(stringResource(R.string.app_about_free_title), style = MaterialTheme.typography.titleMedium, color = SheafColors.Text)
                        Text(
                            text = stringResource(R.string.app_about_free_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = SheafColors.Muted,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                PrimaryButton(
                    text = stringResource(R.string.app_about_coffee),
                    onClick = { BrandLinks.openUrl(context, BrandLinks.COFFEE_URL) },
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    tall = false
                )
            }
        }

        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                SecondaryButton(
                    text = stringResource(R.string.app_about_rate),
                    icon = Icons.Filled.Star,
                    onClick = { BrandLinks.openUrl(context, BrandLinks.PLAY_LISTING) },
                    modifier = Modifier.fillMaxWidth(),
                    tall = false
                )
                SecondaryButton(
                    text = stringResource(R.string.app_about_privacy),
                    icon = Icons.Filled.Lock,
                    onClick = { BrandLinks.openUrl(context, BrandLinks.PRIVACY_URL) },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    tall = false
                )
                TextButton(onClick = { BrandLinks.openUrl(context, BrandLinks.WEBSITE_URL) }) {
                    Text(stringResource(R.string.app_about_made_by, BrandLinks.BRAND_LABEL, BrandLinks.WEBSITE_URL.removePrefix("https://")), color = SheafColors.Dim, style = MaterialTheme.typography.bodySmall)
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
            stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = SheafColors.Muted,
            textAlign = TextAlign.Center
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 12.dp)) {
            Pill(stringResource(R.string.app_about_version, BuildConfig.VERSION_NAME), SheafColors.Muted)
            Pill(pluralStringResource(R.plurals.app_tool_count, ToolId.entries.size, ToolId.entries.size), SheafColors.Band)
            Pill(stringResource(R.string.app_pill_no_internet), SheafColors.Done)
        }
        Spacer(Modifier.height(2.dp))
    }
}
