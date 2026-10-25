package com.layerbit.sheaf.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.stringResource
import com.layerbit.sheaf.R
import com.layerbit.sheaf.SheafApplication
import com.layerbit.sheaf.ui.theme.SheafColors

/**
 * Wraps "start a job" so the first one asks for the notification permission.
 *
 * Asked here rather than at launch because this is the first moment a progress notification
 * is worth anything, and the one-line reason makes sense to the person reading it. Asked once:
 * whatever they answer, the job runs - a refusal only means the progress lives in the app's
 * own progress bar and not in the notification shade. The foreground service that keeps a
 * long job alive does not depend on the permission, so nothing about the work changes.
 *
 * Returns the function to call in place of the job's own start.
 */
@Composable
fun rememberNotificationGate(): (start: () -> Unit) -> Unit {
    val context = LocalContext.current
    val prompts = (context.applicationContext as SheafApplication).prompts
    var waiting by remember { mutableStateOf<(() -> Unit)?>(null) }
    var explaining by remember { mutableStateOf(false) }

    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        // Granted or not, the job the user asked for goes ahead.
        waiting?.invoke()
        waiting = null
    }

    if (explaining) {
        AlertDialog(
            onDismissRequest = {
                explaining = false
                waiting?.invoke()
                waiting = null
            },
            containerColor = SheafColors.Surface,
            title = { Text(stringResource(R.string.app_notify_title), color = SheafColors.Text, style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    stringResource(R.string.app_notify_body),
                    color = SheafColors.Muted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    explaining = false
                    request.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text(stringResource(R.string.app_continue), color = SheafColors.BandBright) }
            },
            dismissButton = {
                TextButton(onClick = {
                    explaining = false
                    waiting?.invoke()
                    waiting = null
                }) { Text(stringResource(R.string.app_not_now), color = SheafColors.Muted) }
            }
        )
    }

    return remember(prompts) {
        { start ->
            val needsAsking = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !prompts.askedNotifications &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (needsAsking) {
                prompts.askedNotifications = true
                waiting = start
                explaining = true
            } else {
                start()
            }
        }
    }
}
