package com.rescuemesh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rescuemesh.domain.Guide
import com.rescuemesh.domain.PrepareGuides

/**
 * Prepare hub screens (Prompt 10). Opened from the Home card
 * ("Prepare — offline guides") through the existing push-stack — the
 * bottom bar keeps its 4 tabs, there is deliberately NO 5th tab.
 * No login, no network: guides are bundled, Saved is Room.
 */

/** The guide list: hazard chips + search + Saved, per the starter sheet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrepareListScreen(viewModel: AppViewModel, prepareViewModel: PrepareViewModel) {
    val guides by prepareViewModel.guides.collectAsState()
    val query by prepareViewModel.query.collectAsState()
    val hazard by prepareViewModel.hazard.collectAsState()
    val savedOnly by prepareViewModel.savedOnly.collectAsState()
    val savedIds by prepareViewModel.savedIds.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(RmSpacing.SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(RmSpacing.SECTION_GAP)
    ) {
        item {
            ScreenHeader(
                "Prepare",
                "Offline guides for before a disaster — no internet needed. ${PrepareGuides.ALL.size} guides, bundled in the app."
            )
        }
        item {
            RmTextField(
                value = query,
                onValueChange = { prepareViewModel.setQuery(it) },
                label = "Search guides",
                placeholder = "Search — e.g. burns, water, meeting point",
                singleLine = true
            )
        }
        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                RmFilterChip(
                    label = "All",
                    selected = hazard == null && !savedOnly,
                    onClick = {
                        prepareViewModel.setSavedOnly(false)
                        prepareViewModel.setHazard(null)
                    }
                )
                RmFilterChip(
                    label = "Saved",
                    selected = savedOnly,
                    onClick = { prepareViewModel.setSavedOnly(!savedOnly) }
                )
                PrepareGuides.HAZARDS.forEach { option ->
                    RmFilterChip(
                        label = PrepareGuides.hazardLabel(option),
                        selected = hazard == option && !savedOnly,
                        onClick = {
                            prepareViewModel.setSavedOnly(false)
                            prepareViewModel.setHazard(option)
                        }
                    )
                }
            }
        }
        if (guides.isEmpty()) {
            item {
                Text(
                    if (savedOnly) "No saved guides yet — open a guide and tap Save."
                    else "No guides match that search.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = RescueColors.Muted
                )
            }
        }
        items(guides) { guide ->
            GuideCard(
                guide = guide,
                saved = guide.id in savedIds,
                onClick = { viewModel.pushSubScreen(SubScreen.PrepareDetail(guide.id)) },
                onToggleSaved = { prepareViewModel.toggleSaved(guide.id) }
            )
        }
    }
}

/** One guide row: title, summary, hazard chip and Saved toggle. */
@Composable
private fun GuideCard(
    guide: Guide,
    saved: Boolean,
    onClick: () -> Unit,
    onToggleSaved: () -> Unit
) {
    RmCard(onClick = onClick) {
        Kicker(
            if (guide.hazard.isBlank()) "RescueMesh guide"
            else PrepareGuides.hazardLabel(guide.hazard)
        )
        Text(
            guide.title,
            style = TextStyle(
                fontFamily = ArchivoFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 17.sp,
                color = RescueColors.Ink
            )
        )
        Text(
            guide.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = RescueColors.Muted
        )
        SecondaryButton(onClick = onToggleSaved, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (saved) "Saved ✓ — tap to remove" else "Save for offline",
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/** One guide, fully: the six template sections + its source line. */
@Composable
fun PrepareDetailScreen(guide: Guide, saved: Boolean, onToggleSaved: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(RmSpacing.SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(RmSpacing.SECTION_GAP)
    ) {
        item {
            ScreenHeader(guide.title, guide.summary)
        }
        item {
            SecondaryButton(onClick = onToggleSaved, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (saved) "Saved ✓ — tap to remove" else "Save for offline",
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        guide.sections.forEach { section ->
            item {
                RmCard {
                    Kicker(section.title)
                    section.bullets.forEach { bullet ->
                        Text(
                            "• $bullet",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
        item {
            Text(
                "Source: ${guide.source}. Follow official advice — in an emergency, call 112.",
                style = MaterialTheme.typography.bodySmall,
                color = RescueColors.Muted
            )
        }
    }
}
