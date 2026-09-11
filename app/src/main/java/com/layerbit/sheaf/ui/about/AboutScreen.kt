package com.layerbit.sheaf.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.BuildConfig
import com.layerbit.sheaf.brand.BrandLinks
import com.layerbit.sheaf.ui.components.Panel
import com.layerbit.sheaf.ui.components.SectionHeading
import com.layerbit.sheaf.ui.theme.SheafColors

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("Sheaf", style = MaterialTheme.typography.displayMedium, color = SheafColors.Text)
                Text(
                    // Read from BuildConfig rather than hardcoded, so this always shows the
                    // version actually running.
                    text = "Version ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim
                )
            }
        }

        item {
            Panel(modifier = Modifier.fillMaxWidth()) {
                SectionHeading("Nothing leaves this phone")
                Text(
                    text = "Sheaf does not have permission to use the internet. Not restricted, " +
                        "not disabled in settings - the permission is absent from the app, so " +
                        "the process cannot open a network connection at all.\n\n" +
                        "Your documents are read and written on this device and nowhere else. " +
                        "You can check this yourself: the Play Store listing shows every " +
                        "permission an app declares.",
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
                        "write to us and say what you were doing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SheafColors.Muted,
                    modifier = Modifier.padding(top = 8.dp)
                )
                TextButton(
                    onClick = {
                        BrandLinks.sendEmail(
                            context,
                            subject = "Sheaf ${BuildConfig.VERSION_NAME} - feedback",
                            body = ""
                        )
                    },
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text("Report a problem", color = SheafColors.Band)
                }
            }
        }

        item {
            Panel(modifier = Modifier.fillMaxWidth()) {
                SectionHeading("Free, and staying that way")
                Text(
                    text = "Every tool in Sheaf is free. There are no ads, no subscription and " +
                        "no account, and there is no server behind it to pay for.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SheafColors.Muted,
                    modifier = Modifier.padding(top = 8.dp)
                )
                TextButton(
                    onClick = { BrandLinks.openUrl(context, BrandLinks.COFFEE_URL) },
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text("Buy us a coffee", color = SheafColors.Band)
                }
            }
        }

        item {
            TextButton(onClick = { BrandLinks.openUrl(context, BrandLinks.WEBSITE_URL) }) {
                Text("From ${BrandLinks.BRAND_LABEL}", color = SheafColors.Dim)
            }
        }
    }
}
