package com.layerbit.sheaf.ui.intro

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.R
import com.layerbit.sheaf.ui.components.IconTile
import com.layerbit.sheaf.ui.components.PrimaryButton
import com.layerbit.sheaf.ui.theme.SheafColors
import kotlinx.coroutines.launch

private data class IntroCard(val iconRes: Int, val title: String, val body: String)

private val cards = listOf(
    IntroCard(
        R.drawable.ic_tool_lock,
        "Nothing leaves your phone",
        "Sheaf has no internet permission at all. Every tool runs on this device, so a contract or a bank statement is never uploaded anywhere."
    ),
    IntroCard(
        R.drawable.ic_tool_organise,
        "Every tool is free",
        "Merge, compress, sign, scan, OCR, convert and the rest. No ads, no account, no subscription, and no page limits."
    ),
    IntroCard(
        R.drawable.ic_tool_read,
        "Your original is never changed",
        "Each tool makes a new file and leaves the one you picked exactly as it was. You choose where the result is saved."
    )
)

/**
 * Three cards, once, on the first launch.
 *
 * What a new user needs to believe before they will put a real document through an app they
 * have never heard of: it is offline, it is free, and it cannot spoil the file. Skip is on every
 * card because anyone who already knows has better things to do.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val pager = rememberPagerState { cards.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == cards.lastIndex

    Column(
        modifier = Modifier.fillMaxSize().background(SheafColors.Background).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone) { Text("Skip", color = SheafColors.Muted) }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
            val card = cards[index]
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                IconTile(iconRes = card.iconRes, accent = SheafColors.Band, size = 88.dp, filled = true)
                Text(
                    card.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = SheafColors.Text,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 28.dp).widthIn(max = 480.dp)
                )
                Text(
                    card.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = SheafColors.Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp).widthIn(max = 480.dp)
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(vertical = 20.dp)
                .semantics { contentDescription = "Card ${pager.currentPage + 1} of ${cards.size}" }
        ) {
            cards.indices.forEach { i ->
                val active = i == pager.currentPage
                val width by animateDpAsState(if (active) 22.dp else 8.dp, label = "dot")
                val colour by animateColorAsState(if (active) SheafColors.Band else SheafColors.BorderStrong, label = "dot")
                Box(Modifier.height(8.dp).size(width = width, height = 8.dp).background(colour, CircleShape))
            }
        }
        PrimaryButton(
            text = if (last) "Start" else "Next",
            onClick = {
                if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            },
            modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp)
        )
        Spacer(Modifier.height(8.dp))
    }
}
