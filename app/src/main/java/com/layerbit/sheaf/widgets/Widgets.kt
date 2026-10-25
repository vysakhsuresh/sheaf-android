package com.layerbit.sheaf.widgets

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.layerbit.sheaf.MainActivity
import com.layerbit.sheaf.R
import com.layerbit.sheaf.data.db.RecentEntity
import com.layerbit.sheaf.data.db.SheafDatabase
import kotlinx.coroutines.flow.first

/**
 * Sheaf's two home-screen widgets.
 *
 * Both only ever start MainActivity with an intent it already understands - a sheaf:// Uri
 * like the launcher shortcuts use, or a VIEW on a document Sheaf holds a grant for - so a
 * widget is a shortcut with a face and never a second way into the app's internals.
 *
 * The colours are the dark palette's, fixed, rather than read from the app's theme: a widget
 * is drawn by the launcher process, which cannot see the app's in-memory palette, and ink and
 * magenta sit well on wallpaper of any kind.
 */
private val Ink = ColorProvider(Color(0xFF0B1220))
private val Card = ColorProvider(Color(0xFF141E33))
private val Band = ColorProvider(Color(0xFFE62E80))
private val TextColour = ColorProvider(Color(0xFFF3F6FB))
private val MutedColour = ColorProvider(Color(0xFFA3B1C8))

/** An explicit intent to MainActivity, so nothing outside Sheaf can answer it. */
private fun sheafIntent(context: Context, data: Uri): Intent =
    Intent(Intent.ACTION_VIEW, data).setComponent(ComponentName(context, MainActivity::class.java))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

/** 2 x 1: Scan, and Open a PDF. The two things people come to the app for. */
class QuickActionsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                Row(
                    modifier = GlanceModifier.fillMaxSize().background(Ink).cornerRadius(20.dp).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ActionButton(
                        label = context.getString(R.string.app_home_scan),
                        icon = R.drawable.ic_shortcut_scan,
                        intent = sheafIntent(context, Uri.parse("sheaf://tool/SCAN")),
                        modifier = GlanceModifier.defaultWeight()
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    ActionButton(
                        label = context.getString(R.string.app_home_open_pdf),
                        icon = R.drawable.ic_notification,
                        intent = sheafIntent(context, Uri.parse("sheaf://tool/${MainActivity.PICK_DOCUMENT}")),
                        modifier = GlanceModifier.defaultWeight(),
                        primary = true
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, icon: Int, intent: Intent, modifier: GlanceModifier, primary: Boolean = false) {
    Row(
        modifier = modifier
            .height(48.dp)
            .background(if (primary) Band else Card)
            .cornerRadius(14.dp)
            .clickable(actionStartActivity(intent))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(provider = ImageProvider(icon), contentDescription = null, modifier = GlanceModifier.size(20.dp))
        Spacer(GlanceModifier.width(6.dp))
        Text(label, style = TextStyle(color = TextColour, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

/** 4 x 2: the documents being read, newest first, each one tap from where it was left. */
class RecentsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val recents = runCatching {
            SheafDatabase.get(context).recentDao().observeRecents(limit = 8).first()
        }.getOrDefault(emptyList())
        provideContent {
            GlanceTheme { RecentsContent(context, recents) }
        }
    }
}

@Composable
private fun RecentsContent(context: Context, recents: List<RecentEntity>) {
    Column(modifier = GlanceModifier.fillMaxSize().background(Ink).cornerRadius(20.dp).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                context.getString(R.string.app_widget_recent_title),
                style = TextStyle(color = TextColour, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.defaultWeight()
            )
            Box(
                modifier = GlanceModifier
                    .background(Band).cornerRadius(10.dp).padding(horizontal = 10.dp, vertical = 4.dp)
                    .clickable(actionStartActivity(sheafIntent(context, Uri.parse("sheaf://tool/${MainActivity.PICK_DOCUMENT}"))))
            ) {
                Text(context.getString(R.string.app_widget_open), style = TextStyle(color = TextColour, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        if (recents.isEmpty()) {
            Text(
                context.getString(R.string.app_widget_empty),
                style = TextStyle(color = MutedColour, fontSize = 13.sp)
            )
        } else {
            LazyColumn {
                items(recents, itemId = { it.uri.hashCode().toLong() }) { recent ->
                    val pages = context.resources.getQuantityString(R.plurals.app_page_count, recent.pageCount, recent.pageCount)
                    val details = if (recent.lastPage > 0) context.getString(R.string.app_widget_recent_at_page, pages, recent.lastPage + 1) else pages
                    Column(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clickable(actionStartActivity(sheafIntent(context, Uri.parse(recent.uri))))
                    ) {
                        Column(modifier = GlanceModifier.fillMaxWidth().background(Card).cornerRadius(10.dp).padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(recent.displayName, style = TextStyle(color = TextColour, fontSize = 14.sp), maxLines = 1)
                            Text(details, style = TextStyle(color = MutedColour, fontSize = 12.sp), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

class QuickActionsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickActionsWidget()
}

class RecentsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecentsWidget()
}

/**
 * Redraws the recents widget, after the list changes. Cheap when no widget is placed: Glance
 * finds no ids and returns.
 */
suspend fun refreshRecentsWidget(context: Context) {
    runCatching {
        if (GlanceAppWidgetManager(context).getGlanceIds(RecentsWidget::class.java).isNotEmpty()) {
            RecentsWidget().updateAll(context)
        }
    }
}
