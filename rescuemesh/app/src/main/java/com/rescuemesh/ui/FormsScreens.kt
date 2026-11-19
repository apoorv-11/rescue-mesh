package com.rescuemesh.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rescuemesh.crypto.DeviceIds
import com.rescuemesh.crypto.HopTrace
import com.rescuemesh.crypto.JsonParser
import com.rescuemesh.data.local.MessageEntity
import com.rescuemesh.data.repository.MessageCreationResult
import com.rescuemesh.domain.FoundLinkage
import com.rescuemesh.domain.MessageFactory
import com.rescuemesh.domain.PayloadLocation
import com.rescuemesh.domain.PhotoFull
import com.rescuemesh.domain.PhotoPreview
import com.rescuemesh.domain.PhotoPreviewSource
import com.rescuemesh.domain.PhotoThumb
import com.rescuemesh.domain.Role
import com.rescuemesh.net.LocationFetcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Records & forms — LAYOUT REBUILD ROUND (15 Nov).
 *
 * The list and the five forms are rebuilt element-for-element from
 * the preview (see `ui-replica-blueprint-2026-11-15.md`): the
 * "Records" header + count subtitle, RED-selected filter chips
 * (All / SOS / Missing / Found / Notices / Resources), rec-cards
 * with 52 dp thumbs (photo, or the preview's gradient icon tiles),
 * the photo-ok mint pill, the meta row (state chip left, time +
 * hops right), the expanded 2-column detail grid under a dashed
 * divider, and the circular FAB with its red-tinted shadow. Forms
 * use [RmTextField] inputs, red-selected form chips, [ToggleRow]
 * toggles, btn-ghost photo attach and the btn-red save.
 *
 * Fields, validation, photo pickers and submit calls are EXACTLY
 * the old ones — AppViewModel → MessageRepository's creation funnel
 * (Room first, signed, state CREATED). No network, no gating: forms
 * work with no account and no network, exactly like the quick SOS.
 *
 * Navigation comes from the app router (AppViewModel.navState): a
 * form is a pushed sub-screen of this tab, so the root's back row
 * and system Back return here; a Created result pops back to the
 * list with the status line; a Refused result (the SOS rate guard)
 * stays visible on the form.
 */

internal val listTimeFormat = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())

/** "14 Nov, 20:59" — the preview's Created / Expires and Home meta format. */
internal val homeTimeFormat = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())

/** "14 Nov · 20:59" — the preview's rec-card meta-row time. */
private val metaTimeFormat = SimpleDateFormat("dd MMM '·' HH:mm", Locale.getDefault())

private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
private val hourFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/** The Records filter chips; a null eventType means "all records". */
internal enum class RecordFilter(val label: String, val eventType: String?) {
    ALL("All", null),
    SOS("SOS", "SOS"),
    MISSING("Missing", "MISSING_PERSON"),
    FOUND("Found", "PERSON_FOUND_UPDATE"),
    ANNOUNCEMENTS("Notices", "ANNOUNCEMENT"),
    RESOURCES("Resources", "RESOURCE_UPDATE")
}

/** Short human label for an event type, used on cards and Home's mini-cards. */
internal fun recordTypeLabel(eventType: String): String = when (eventType) {
    "SOS" -> "SOS"
    "SAFETY_CHECKIN" -> "Safety check-in"
    "MISSING_PERSON" -> "Missing person"
    "PERSON_FOUND_UPDATE" -> "Found update"
    "ANNOUNCEMENT" -> "Announcement"
    "RESOURCE_UPDATE" -> "Resource update"
    else -> eventType
}

/** The preview's priority words for the detail grid (1 Critical, 2 High, 3 Normal). */
internal fun priorityLabel(priority: Int): String = when (priority) {
    1 -> "Critical"
    2 -> "High"
    else -> "Normal"
}

/** One payload string field of a record, or null. */
internal fun payloadString(record: MessageEntity, key: String): String? {
    val payload = runCatching { JsonParser.parse(record.payload) as? Map<*, *> }.getOrNull()
        ?: return null
    return payload[key] as? String
}

/** "IA" from "Ishika Avasthi" — the preview's initials on a Missing thumb tile. */
internal fun initialsFor(name: String?): String? {
    val words = name?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: return null
    if (words.isEmpty()) return null
    return words.take(2).map { it.first().uppercaseChar() }.joinToString("")
}

/**
 * The rec-card's sub line (preview: "Missing person · Age 22 · Last
 * seen near campus gate"): the type label plus the record's key
 * payload details. The title above it stays [summaryFor].
 */
internal fun recordSubline(record: MessageEntity): String {
    val payload = runCatching { JsonParser.parse(record.payload) as? Map<*, *> }.getOrNull()
    fun str(key: String): String? = payload?.get(key) as? String
    fun num(key: String): Number? = payload?.get(key) as? Number
    return when (record.eventType) {
        "SOS" -> listOfNotNull(
            "SOS",
            "Priority ${priorityLabel(record.priority)}",
            str("description")?.takeIf { it.isNotBlank() }?.take(40)
        ).joinToString(" · ")
        "SAFETY_CHECKIN" -> listOfNotNull(
            "Safety check-in",
            str("note")?.takeIf { it.isNotBlank() }?.take(40)
        ).joinToString(" · ")
        "MISSING_PERSON" -> listOfNotNull(
            "Missing person",
            num("age")?.let { "Age ${it.toInt()}" },
            str("lastSeenArea")?.takeIf { it.isNotBlank() }?.let { area ->
                if (area.startsWith("near", ignoreCase = true)) "Last seen $area"
                else "Last seen near $area"
            }
        ).joinToString(" · ")
        "PERSON_FOUND_UPDATE" -> listOfNotNull(
            "Found person",
            str("caseId")?.let { "Matched to an open case" },
            str("note")?.takeIf { it.isNotBlank() }?.take(40)
        ).joinToString(" · ")
        "ANNOUNCEMENT" -> listOfNotNull(
            "Notice",
            str("category")?.takeIf { it.isNotBlank() },
            str("text")?.takeIf { it.isNotBlank() }?.take(40)
        ).joinToString(" · ")
        "RESOURCE_UPDATE" -> listOfNotNull(
            "Resource",
            str("resourceType")?.takeIf { it.isNotBlank() },
            num("quantity")?.let { "× ${it.toInt()}" }
        ).joinToString(" · ")
        else -> recordTypeLabel(record.eventType)
    }
}

/** The pushed form of this tab, or the filterable records list at the tab root. */
@Composable
fun RecordsScreen(viewModel: AppViewModel) {
    val nav by viewModel.navState.collectAsState()
    var listStatus by remember { mutableStateOf<String?>(null) }
    val form = (nav.current as? SubScreen.Form)?.kind
    if (form != null) {
        val onCreated: () -> Unit = {
            listStatus = "Saved on this phone (Room). It will relay to nearby phones and sync when a gateway is available."
            viewModel.popSubScreen()
        }
        when (form) {
            FormKind.SOS -> SosFormScreen(viewModel, onCreated)
            FormKind.MISSING -> MissingFormScreen(viewModel, onCreated)
            FormKind.FOUND -> FoundFormScreen(viewModel, onCreated)
            FormKind.ANNOUNCEMENT -> AnnouncementFormScreen(viewModel, onCreated)
            FormKind.RESOURCE -> ResourceFormScreen(viewModel, onCreated)
        }
        return
    }
    RecordsList(viewModel, listStatus)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RecordsList(viewModel: AppViewModel, status: String?) {
    val all by viewModel.messages.collectAsState()
    val photoDeliveredIds by viewModel.photoDeliveredIds.collectAsState()
    // FIX6 mark: which Missing cases already have a found report
    // stored on this phone (own or relayed) — same local linkage as
    // the Found picker's cap.
    val foundUpdates by viewModel.messagesOfType("PERSON_FOUND_UPDATE").collectAsState()
    val foundLinkedIds = remember(foundUpdates) { FoundLinkage.linkedCaseIds(foundUpdates) }
    var filter by remember { mutableStateOf(RecordFilter.ALL) }
    var expandedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showChooser by remember { mutableStateOf(false) }
    val records = all.filter { filter.eventType == null || it.eventType == filter.eventType }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(RmSpacing.SCREEN_PADDING),
            verticalArrangement = Arrangement.spacedBy(RmSpacing.SECTION_GAP)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScreenHeader(
                        "Records",
                        "${all.size} stored on this phone, newest first. Tap a card to expand it."
                    )
                    status?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            item {
                // The preview's .fchip row: white pills, RED when
                // selected, wrapping — never clipped at the edge.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    RecordFilter.values().forEach { option ->
                        RmFilterChip(
                            label = option.label,
                            selected = filter == option,
                            onClick = { filter = option }
                        )
                    }
                }
            }
            items(records) { record ->
                RecordCard(
                    viewModel = viewModel,
                    record = record,
                    expanded = record.messageId in expandedIds,
                    photoDelivered = record.messageId in photoDeliveredIds,
                    foundLinked = record.eventType == "MISSING_PERSON" &&
                        record.messageId in foundLinkedIds,
                    onToggle = {
                        expandedIds = if (record.messageId in expandedIds) {
                            expandedIds - record.messageId
                        } else {
                            expandedIds + record.messageId
                        }
                    }
                )
            }
            if (records.isEmpty()) {
                item {
                    Text(
                        "No records of this type yet — tap + to create one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = RescueColors.Muted
                    )
                }
            }
        }
        // The preview's FAB: a 54 dp red CIRCLE with a red-tinted
        // shadow (0 10px 22px rgba(229,50,45,.45)) and a white +.
        Box(
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = 18.dp)
                .size(54.dp)
                .shadow(
                    elevation = 10.dp,
                    shape = CircleShape,
                    clip = false,
                    ambientColor = RescueColors.RescueRed.copy(alpha = 0.45f),
                    spotColor = RescueColors.RescueRed.copy(alpha = 0.45f)
                )
                .clip(CircleShape)
                .background(RescueColors.RescueRed)
                .clickable { showChooser = true },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "+",
                style = TextStyle(
                    fontFamily = ManropeFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 28.sp,
                    color = Color.White
                )
            )
        }
        if (showChooser) {
            ModalBottomSheet(
                onDismissRequest = { showChooser = false },
                sheetState = rememberModalBottomSheetState()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Kicker("New record")
                    listOf(
                        FormKind.SOS to "SOS form",
                        FormKind.MISSING to "Missing person form",
                        FormKind.FOUND to "Found update",
                        FormKind.ANNOUNCEMENT to "Announcement form",
                        FormKind.RESOURCE to "Resource update"
                    ).forEach { (kind, label) ->
                        SecondaryButton(
                            onClick = {
                                showChooser = false
                                viewModel.pushSubScreen(SubScreen.Form(kind))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(label, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One record as a preview rec-card: white, radius 20, state-coloured
 * left border; a 52 dp thumb (photo, or the type's gradient tile),
 * title + sub line, the photo-ok mint pill, and the meta row (state
 * chip left, time · hops right — "Expanded view" when open).
 * Expanded adds the photo section, a dashed divider, and the
 * 2-column label/value detail grid: Priority, Hops (with the last
 * hop's RM label), Created, Expires, the wide Origin · Signature
 * row, the Message ID, and every payload field as pairs.
 */
@Composable
private fun RecordCard(
    viewModel: AppViewModel,
    record: MessageEntity,
    expanded: Boolean,
    photoDelivered: Boolean,
    onToggle: () -> Unit,
    foundLinked: Boolean = false
) {
    val hasPhotoSection = record.eventType == "MISSING_PERSON" ||
        record.eventType == "PERSON_FOUND_UPDATE"
    val photoState = rememberRecordPhotoState(viewModel, record)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .rmSurface(RoundedCornerShape(20.dp))
            .clickable { onToggle() }
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            StateBorder(record.localState)
            Column(
                modifier = Modifier.weight(1f)
                    .padding(horizontal = 12.dp, vertical = 11.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    RecordTile(record, photoState, size = 52.dp, allowDialog = true)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            summaryFor(record),
                            style = TextStyle(
                                fontFamily = ManropeFamily,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp,
                                color = RescueColors.Ink
                            ),
                            maxLines = if (expanded) Int.MAX_VALUE else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            recordSubline(record),
                            style = TextStyle(
                                fontFamily = ManropeFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.5.sp,
                                color = RescueColors.Muted
                            ),
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        // Prompt 10 Emergency screen (surfacing only):
                        // the record's state in plain words. Same line
                        // as the Home mini-cards.
                        Text(
                            com.rescuemesh.domain.PrepareGuides.statusLineFor(record.localState),
                            style = TextStyle(
                                fontFamily = ManropeFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp,
                                color = RescueColors.Muted
                            )
                        )
                        // FIX6 mark: this Missing case already has a
                        // found report stored on this phone. Same mint
                        // pill style as the photo proof, right under
                        // the status line — list and expanded view.
                        if (foundLinked) {
                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = RescueColors.MintTint,
                                modifier = Modifier.padding(top = 5.dp)
                            ) {
                                Text(
                                    "Found reported",
                                    style = TextStyle(
                                        fontFamily = ManropeFamily,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 11.sp,
                                        color = Color(0xFF0B6B4F)
                                    ),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    if (expanded) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Collapse",
                            tint = Color(0xFF9AA0A8),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                // The photo-ok mint pill (preview .photo-ok): which of
                // the two photo proofs this phone holds, in one pill —
                // it replaces the status texts the expanded photo
                // section used to print under the image.
                if (hasPhotoSection && (photoState.hasFullPhoto || photoDelivered)) {
                    val pillText = listOfNotNull(
                        if (photoState.hasFullPhoto) "Full photo saved ✓" else null,
                        if (photoDelivered) "Photo delivered ✓" else null
                    ).joinToString(" · ")
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = RescueColors.MintTint,
                        modifier = Modifier.padding(top = 7.dp)
                    ) {
                        Text(
                            pillText,
                            style = TextStyle(
                                fontFamily = ManropeFamily,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 11.sp,
                                color = Color(0xFF0B6B4F)
                            ),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StateChip(record.localState)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (expanded) {
                            "Expanded view"
                        } else {
                            "${metaTimeFormat.format(Date(record.createdAt))} · " +
                                "hops ${record.hopCount}/${record.hopLimit}"
                        },
                        style = TextStyle(
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = RescueColors.Muted
                        )
                    )
                }
                if (expanded) {
                    if (hasPhotoSection) {
                        Spacer(Modifier.height(9.dp))
                        RecordPhotoSection(photoState)
                    }
                    DashedDivider(modifier = Modifier.padding(top = 9.dp))
                    RecordDetailGrid(record)
                }
            }
        }
    }
}

/** The expanded card's detail grid (preview .rec-detail). */
@Composable
private fun RecordDetailGrid(record: MessageEntity) {
    val trace = runCatching { HopTrace.parseTrace(record.hopTrace) }.getOrNull()
    val viaLabel = trace?.lastOrNull()?.deviceId?.let { DeviceIds.advertisedLabel(it) }
    val hopsValue = "${record.hopCount} / ${record.hopLimit}" +
        if (record.hopCount > 0 && viaLabel != null) " · via $viaLabel" else ""
    val originValue = "${record.originDeviceId.take(13)}… · " +
        if (record.signature.isNotBlank()) "Verified ✓" else "Not signed"
    val fields = payloadFields(record)
    Column(
        modifier = Modifier.padding(top = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DetailCell("Priority", priorityLabel(record.priority), Modifier.weight(1f))
            DetailCell("Hops", hopsValue, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DetailCell(
                "Created", homeTimeFormat.format(Date(record.createdAt)), Modifier.weight(1f)
            )
            DetailCell(
                "Expires", homeTimeFormat.format(Date(record.expiresAt)), Modifier.weight(1f)
            )
        }
        DetailCell("Origin · Signature", originValue, Modifier.fillMaxWidth())
        DetailCell("Message ID", record.messageId, Modifier.fillMaxWidth())
        fields.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DetailCell(pair[0].first, pair[0].second, Modifier.weight(1f))
                if (pair.size > 1) {
                    DetailCell(pair[1].first, pair[1].second, Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * The scalar payload fields of a record for the expanded card, in
 * payload order. The photo keys are skipped — the photo section
 * represents them (the thumbnail is never raw text, and the photoRef
 * object is routing metadata, not a field a reader needs).
 */
private fun payloadFields(record: MessageEntity): List<Pair<String, String>> {
    val payload = runCatching { JsonParser.parse(record.payload) as? Map<*, *> }.getOrNull()
        ?: return emptyList()
    val skip = setOf("photoThumb", "photoRef", "photoOmitted")
    return payload.entries.mapNotNull { (key, value) ->
        val name = key as? String ?: return@mapNotNull null
        if (name in skip) return@mapNotNull null
        val rendered = when (value) {
            null -> return@mapNotNull null
            is String -> value
            is Number, is Boolean -> value.toString()
            is List<*> -> value.joinToString(", ")
            is Map<*, *> -> return@mapNotNull null
            else -> value.toString()
        }
        if (rendered.isBlank()) null else name to rendered
    }
}

// ------------------------------------------------------------------ photos

/**
 * Everything a record card needs to show its photo, loaded once per
 * record: the decoded payload thumbnail, the held full photo (full
 * bitmap for the dialog + a downscaled copy for previews), and the
 * [PhotoPreview] decision over them. All Bitmap work runs off the main
 * thread. Internal: Home's mini-records use the same state.
 */
internal class RecordPhotoState(
    val thumb: ImageBitmap?,
    val fullBitmap: ImageBitmap?,
    val previewFullBitmap: ImageBitmap?,
    val hasFullPhoto: Boolean,
    val preview: PhotoPreviewSource
)

@Composable
internal fun rememberRecordPhotoState(viewModel: AppViewModel, record: MessageEntity): RecordPhotoState {
    val thumb = remember(record.messageId, record.payload) {
        decodePhotoThumb(record)
    }
    var fullBytes by remember(record.messageId) { mutableStateOf<ByteArray?>(null) }
    var fullBitmap by remember(record.messageId) { mutableStateOf<ImageBitmap?>(null) }
    var previewFullBitmap by remember(record.messageId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(record.messageId) {
        // Load the held photo's bytes, decode the full image (for the
        // tap-to-open dialog) and a downscaled copy (card previews).
        val loaded = withContext(Dispatchers.IO) {
            val bytes = viewModel.fullPhotoBytes(record.messageId)
            Triple(
                bytes,
                bytes?.let {
                    runCatching {
                        BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                    }.getOrNull()
                },
                bytes?.let { decodeDownscaledPhoto(it, CARD_PREVIEW_MAX_SIDE_PX) }
            )
        }
        fullBytes = loaded.first
        fullBitmap = loaded.second
        previewFullBitmap = loaded.third
    }
    return RecordPhotoState(
        thumb = thumb,
        fullBitmap = fullBitmap,
        previewFullBitmap = previewFullBitmap,
        hasFullPhoto = fullBytes != null,
        preview = PhotoPreview.choose(
            hasThumbnail = thumb != null,
            hasCompletePhoto = fullBytes != null,
            photoOmitted = photoWasOmitted(record)
        )
    )
}

/**
 * A record's square thumb at [size] (52 dp on rec-cards, 40 dp on
 * Home's mini-recs): the real photo when [RecordPhotoState.preview]
 * has one (tap opens the full photo when [allowDialog] and this phone
 * holds it), otherwise the preview's type tile — Missing = red
 * gradient with the person's initials (or the person glyph), Found =
 * green gradient with a check, SOS = ink tile with "SOS", Safety =
 * green with the shield-check, Announcement = amber with "!", and
 * Resource = blue with a box glyph. The preview only draws the
 * Missing / Found / SOS tiles; the other three extend its language
 * to the remaining types.
 */
@Composable
internal fun RecordTile(
    record: MessageEntity,
    state: RecordPhotoState,
    size: Dp,
    allowDialog: Boolean
) {
    val radius = if (size >= 52.dp) 14.dp else 12.dp
    val bitmap = when (state.preview) {
        PhotoPreviewSource.THUMBNAIL -> state.thumb
        PhotoPreviewSource.FULL_PHOTO -> state.previewFullBitmap ?: state.fullBitmap
        else -> null
    }
    var showFull by remember { mutableStateOf(false) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "Photo attached to this record",
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(radius))
                .clickable(enabled = allowDialog && state.fullBitmap != null) { showFull = true },
            contentScale = ContentScale.Crop
        )
        if (showFull && state.fullBitmap != null) {
            FullPhotoDialog(state.fullBitmap) { showFull = false }
        }
        return
    }
    val glyphSize = size * 0.5f
    val tileModifier = when (record.eventType) {
        "MISSING_PERSON" -> Modifier.background(
            Brush.linearGradient(listOf(Color(0xFFF3C6C4), RescueColors.RescueRed))
        )
        "PERSON_FOUND_UPDATE" -> Modifier.background(
            Brush.linearGradient(listOf(Color(0xFFA7E8CB), RescueColors.SafeGreen))
        )
        "SOS" -> Modifier.background(RescueColors.Ink)
        "SAFETY_CHECKIN" -> Modifier.background(RescueColors.SafeGreen)
        "ANNOUNCEMENT" -> Modifier.background(
            Brush.linearGradient(listOf(Color(0xFFFDE68A), Color(0xFFF59E0B)))
        )
        "RESOURCE_UPDATE" -> Modifier.background(Color(0xFF2563EB))
        else -> Modifier.background(RescueColors.Muted)
    }
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(radius)).then(tileModifier),
        contentAlignment = Alignment.Center
    ) {
        when (record.eventType) {
            "MISSING_PERSON" -> {
                val initials = initialsFor(payloadString(record, "name"))
                if (initials != null) {
                    Text(
                        initials,
                        style = TextStyle(
                            fontFamily = ArchivoFamily,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (size >= 52.dp) 13.sp else 12.sp,
                            color = Color.White
                        )
                    )
                } else {
                    StrokeGlyph(modifier = Modifier.size(glyphSize), color = Color.White) {
                        drawCircle(
                            Color.White, radius = 3.6f,
                            center = androidx.compose.ui.geometry.Offset(12f, 8.5f),
                            style = Stroke(width = 2.2f)
                        )
                        drawPath(
                            personPath(), Color.White,
                            style = Stroke(
                                width = 2.2f,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                        )
                    }
                }
            }
            "PERSON_FOUND_UPDATE" -> StrokeGlyph(
                modifier = Modifier.size(glyphSize), color = Color.White, strokeWidth = 2.4f
            ) {
                val check = Path().apply {
                    moveTo(5f, 12.5f)
                    lineTo(10f, 17.5f)
                    lineTo(19f, 7f)
                }
                drawPath(
                    check, Color.White,
                    style = Stroke(
                        width = 2.4f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round
                    )
                )
            }
            "SOS" -> Text(
                "SOS",
                style = TextStyle(
                    fontFamily = ArchivoFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (size >= 52.dp) 12.sp else 10.sp,
                    color = Color.White
                )
            )
            "SAFETY_CHECKIN" -> StrokeGlyph(
                modifier = Modifier.size(glyphSize), color = Color.White, strokeWidth = 2.3f
            ) {
                drawPath(
                    shieldCheckPath(), Color.White,
                    style = Stroke(
                        width = 2.3f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round
                    )
                )
            }
            "ANNOUNCEMENT" -> Text(
                "!",
                style = TextStyle(
                    fontFamily = ArchivoFamily,
                    fontWeight = FontWeight.Black,
                    fontSize = if (size >= 52.dp) 16.sp else 13.sp,
                    color = Color.White
                )
            )
            "RESOURCE_UPDATE" -> StrokeGlyph(
                modifier = Modifier.size(glyphSize), color = Color.White
            ) {
                val boxPath = Path().apply {
                    moveTo(4f, 8f)
                    lineTo(12f, 4f)
                    lineTo(20f, 8f)
                    lineTo(20f, 16f)
                    lineTo(12f, 20f)
                    lineTo(4f, 16f)
                    close()
                    moveTo(4f, 8f)
                    lineTo(12f, 12f)
                    lineTo(20f, 8f)
                    moveTo(12f, 12f)
                    lineTo(12f, 20f)
                }
                drawPath(
                    boxPath, Color.White,
                    style = Stroke(
                        width = 2.1f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round
                    )
                )
            }
            else -> Text(
                recordTypeLabel(record.eventType).take(2).uppercase(),
                style = TextStyle(
                    fontFamily = ArchivoFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 11.sp,
                    color = Color.White
                )
            )
        }
    }
}

/**
 * The photo block of an expanded Missing / Found card. What fills the
 * preview slot is [PhotoPreview]'s decision (unit-tested): the payload
 * thumbnail decoded and shown as an image (the photo is payload data,
 * not text — before the TG-4 fix nothing decoded photoThumb anywhere,
 * so the photo was invisible in the app and the raw Base64 in the debug
 * dump was its only trace); when there is NO usable thumbnail but this
 * phone holds the COMPLETE full photo, the preview decodes from that
 * (downscaled for the card) — a detailed photo whose thumbnail could
 * not be produced still shows its picture (device fix, 14 Nov); when a
 * photo was picked but nothing could be kept, the honest omitted note.
 * Tapping the preview opens the full photo when this phone holds it.
 * The "Full photo saved ✓ / Photo delivered ✓" proofs now live in the
 * card's photo-ok mint pill, so they are not repeated here.
 */
@Composable
private fun RecordPhotoSection(state: RecordPhotoState) {
    var showFull by remember { mutableStateOf(false) }
    when (state.preview) {
        PhotoPreviewSource.THUMBNAIL -> Image(
            bitmap = state.thumb!!,
            contentDescription = "Photo attached to this record",
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(enabled = state.fullBitmap != null) { showFull = true },
            contentScale = ContentScale.Crop
        )
        PhotoPreviewSource.FULL_PHOTO -> {
            val bitmap = state.previewFullBitmap ?: state.fullBitmap
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Photo attached to this record",
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = state.fullBitmap != null) { showFull = true },
                    contentScale = ContentScale.Crop
                )
            }
        }
        PhotoPreviewSource.OMITTED_NOTE -> Text(
            "Photo omitted (too large to relay)",
            style = MaterialTheme.typography.bodySmall
        )
        PhotoPreviewSource.NONE -> Unit
    }
    if (showFull && state.fullBitmap != null) {
        FullPhotoDialog(state.fullBitmap) { showFull = false }
    }
}

@Composable
private fun FullPhotoDialog(bitmap: ImageBitmap, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Image(
                bitmap = bitmap,
                contentDescription = "Full photo attached to this record",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/** One-line summary of a record for lists and the Found picker, from its payload. */
internal fun summaryFor(record: MessageEntity): String {
    val payload = runCatching { JsonParser.parse(record.payload) as? Map<*, *> }.getOrNull()
    fun str(key: String): String? = payload?.get(key) as? String
    fun num(key: String): Number? = payload?.get(key) as? Number
    return when (record.eventType) {
        "SOS" -> listOfNotNull(
            str("category"),
            str("description")?.takeIf { it.isNotBlank() }?.take(60),
            num("peopleCount")?.let { "$it people" }
        ).joinToString(" · ").ifBlank { "SOS" }
        "SAFETY_CHECKIN" -> str("note") ?: "I am safe"
        "MISSING_PERSON" -> str("name")
            ?: str("description")?.take(60)
            ?: "Missing person"
        "PERSON_FOUND_UPDATE" -> "Found update — " + (str("note")?.take(60) ?: "")
        "ANNOUNCEMENT" -> listOfNotNull(str("category"), str("text")?.take(60)).joinToString(" · ")
        "RESOURCE_UPDATE" -> listOfNotNull(
            str("resourceType"),
            num("quantity")?.let { "× $it" },
            str("note")?.take(40)
        ).joinToString(" · ")
        else -> record.eventType
    }
}

/**
 * Decodes a record's `photoThumb` payload value (Missing and Found
 * records carry one) into a displayable bitmap, or null when there is
 * no photo or it does not decode. Thumbnails this app produces are
 * Base64url without padding
 * ([com.rescuemesh.domain.PhotoThumb]); the MIME decoder fallback
 * tolerates the standard alphabet / padded / wrapped forms, so a
 * thumbnail written by any producer still renders. The raw Base64
 * string itself is never shown as text.
 */
private fun decodePhotoThumb(record: MessageEntity): ImageBitmap? {
    val payload = runCatching { JsonParser.parse(record.payload) as? Map<*, *> }.getOrNull()
    val encoded = payload?.get("photoThumb") as? String ?: return null
    val bytes = runCatching { DeviceIds.base64UrlDecode(encoded) }
        .getOrElse {
            runCatching { java.util.Base64.getMimeDecoder().decode(encoded) }.getOrNull()
        } ?: return null
    return runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

/** Longest side (px) for a card preview decoded from a full photo: 72 dp at up to ~4x density. */
private const val CARD_PREVIEW_MAX_SIDE_PX = 288

/**
 * Decodes JPEG [bytes] downscaled so the longest side is at most about
 * [maxSidePx] (power-of-two sample size), for cheap card previews of a
 * full photo; null when the bytes do not decode.
 */
private fun decodeDownscaledPhoto(bytes: ByteArray, maxSidePx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / (sampleSize * 2) >= maxSidePx) sampleSize *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }.getOrNull()
}

/** True when the record's payload says a photo was picked but exceeded the relay size cap. */
private fun photoWasOmitted(record: MessageEntity): Boolean {
    val payload = runCatching { JsonParser.parse(record.payload) as? Map<*, *> }.getOrNull()
    return payload?.get("photoOmitted") == true
}

/**
 * The processed result of one photo pick (Missing + Found forms share
 * this; photo-transfer design). [fullJpeg] is the ≤ 500 KB full photo
 * that PhotoStore will hold and the mesh will carry in chunks;
 * [thumbB64] is the small preview derived from that final JPEG;
 * [omitted] is the §7 flag for "a photo was picked but nothing could be
 * kept". A photo problem never blocks the record itself.
 */
private class PickedPhoto(
    val fullJpeg: ByteArray?,
    val thumbB64: String?,
    val omitted: Boolean,
    val note: String
)

/**
 * Runs the photo pipeline for picked [bytes] (null = the picker stream
 * could not be read): try the full photo first (stepped downscale /
 * re-encode to ≤ [PhotoFull.MAX_BYTES]); the thumbnail is derived from
 * that final JPEG. When even the floor rung cannot fit the full photo,
 * fall back to the thumbnail-only path exactly as before; when not even
 * the thumbnail fits, the photo is omitted with a clear note.
 * Bitmap work — call off the main thread.
 */
private fun processPickedPhoto(bytes: ByteArray?): PickedPhoto {
    if (bytes == null) {
        return PickedPhoto(null, null, false, "Photo could not be read")
    }
    val full = PhotoFull.encodeFull(bytes)
    if (full != null) {
        return PickedPhoto(
            fullJpeg = full,
            thumbB64 = PhotoThumb.encodeThumbnail(full),
            omitted = false,
            note = "Photo attached ✓"
        )
    }
    val thumb = PhotoThumb.encodeThumbnail(bytes)
    return if (thumb != null) {
        PickedPhoto(
            fullJpeg = null,
            thumbB64 = thumb,
            omitted = false,
            note = "Photo attached (preview only — full photo too large)"
        )
    } else {
        PickedPhoto(null, null, true, "Photo omitted (too large)")
    }
}

// ------------------------------------------------------------------ forms

@Composable
private fun SosFormScreen(viewModel: AppViewModel, onCreated: () -> Unit) {
    var category by remember { mutableStateOf("OTHER") }
    var description by remember { mutableStateOf("") }
    var peopleCount by remember { mutableStateOf(1) }
    var injured by remember { mutableStateOf(0) }
    var injuredSet by remember { mutableStateOf(false) }
    val needs = remember { mutableStateOf(setOf<String>()) }
    val vulnerable = remember { mutableStateOf(setOf<String>()) }
    var location by remember { mutableStateOf<PayloadLocation?>(null) }
    var refusal by remember { mutableStateOf<String?>(null) }

    FormScaffold {
        Kicker("Category")
        ChipRows(MessageFactory.SOS_CATEGORIES, setOf(category)) { category = it }
        RmTextField(
            value = description,
            onValueChange = { description = it.take(MessageFactory.MAX_SOS_DESCRIPTION_CHARS) },
            label = "Description",
            supportingText = "${description.length}/${MessageFactory.MAX_SOS_DESCRIPTION_CHARS}"
        )
        Stepper("People involved", peopleCount, min = 1, max = 999) {
            peopleCount = it
            if (injured > it) injured = it
        }
        ToggleRow(
            label = "Injured count known",
            checked = injuredSet,
            onCheckedChange = { injuredSet = it }
        )
        if (injuredSet) {
            Stepper("Injured", injured, min = 0, max = peopleCount) { injured = it }
        }
        Kicker("Needs")
        ChipRows(MessageFactory.SOS_NEEDS, needs.value) { needs.value = needs.value.toggle(it) }
        Kicker("Vulnerable people")
        ChipRows(MessageFactory.SOS_VULNERABLE, vulnerable.value) { vulnerable.value = vulnerable.value.toggle(it) }
        LocationFields(viewModel) { location = it }
        refusal?.let { Text(it, color = Color(0xFFB3261E), fontWeight = FontWeight.SemiBold) }
        PrimaryButton(
            onClick = {
                viewModel.submitSosDetailed(
                    category = category,
                    description = description,
                    peopleCount = peopleCount,
                    injured = if (injuredSet) injured else null,
                    needs = needs.value.toList(),
                    vulnerable = vulnerable.value.toList(),
                    location = location
                ) { result ->
                    when (result) {
                        is MessageCreationResult.Created -> onCreated()
                        is MessageCreationResult.Refused -> refusal = result.reason
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save SOS", maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun MissingFormScreen(viewModel: AppViewModel, onCreated: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var ageText by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var lastSeenArea by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf(dayFormat.format(Date())) }
    var timeText by remember { mutableStateOf(hourFormat.format(Date())) }
    var photoThumb by remember { mutableStateOf<String?>(null) }
    var photoOmitted by remember { mutableStateOf(false) }
    var fullPhoto by remember { mutableStateOf<ByteArray?>(null) }
    var photoNote by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // FIX9 Kaam 3: the Missing form had no location input at all — its
    // only "location" was the lastSeenArea text — so a GPS fix could
    // never reach the payload. It now carries LocationFields like the
    // other forms; the fix rides as payload.location next to lastSeenArea.
    var location by remember { mutableStateOf<PayloadLocation?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val picked = withContext(Dispatchers.Default) {
                    val bytes = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                    processPickedPhoto(bytes)
                }
                photoThumb = picked.thumbB64
                photoOmitted = picked.omitted
                fullPhoto = picked.fullJpeg
                photoNote = picked.note
            }
        }
    }

    FormScaffold {
        RmTextField(
            value = name,
            onValueChange = { name = it },
            label = "Name (optional)",
            singleLine = true
        )
        RmTextField(
            value = ageText,
            onValueChange = { ageText = it.filter { c -> c.isDigit() }.take(3) },
            label = "Age (optional)",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        RmTextField(
            value = description,
            onValueChange = { description = it },
            label = "Description"
        )
        RmTextField(
            value = lastSeenArea,
            onValueChange = { lastSeenArea = it },
            label = "Last seen area",
            singleLine = true
        )
        DateTimeFields(dateText, timeText, { dateText = it }, { timeText = it }, label = "Last seen (date / time)")
        LocationFields(viewModel) { location = it }
        SecondaryButton(onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Attach photo", maxLines = 1, softWrap = false)
        }
        photoNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        error?.let { Text(it, color = Color(0xFFB3261E)) }
        PrimaryButton(
            onClick = {
                val lastSeenAt = parseDateTimeMs(dateText, timeText)
                if (lastSeenAt == null) {
                    error = "Last-seen date/time doesn't parse — use yyyy-MM-dd and HH:mm."
                } else {
                    viewModel.submitMissingPerson(
                        name = name.ifBlank { null },
                        age = ageText.toIntOrNull(),
                        description = description,
                        lastSeenArea = lastSeenArea,
                        lastSeenAtMs = lastSeenAt,
                        photoThumbB64 = photoThumb,
                        photoOmitted = photoOmitted,
                        fullPhotoJpeg = fullPhoto,
                        location = location
                    ) { result ->
                        when (result) {
                            is MessageCreationResult.Created -> onCreated()
                            is MessageCreationResult.Refused -> error = result.reason
                        }
                    }
                }
            },
            enabled = description.isNotBlank() && lastSeenArea.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save missing-person report", maxLines = 1, softWrap = false)
        }
    }
}

/**
 * One case row in the Found picker's lists (FIX6): radio + summary +
 * id/time line. The same row is used for the main list and for the
 * collapsed already-reported section — only the tap behaviour differs
 * (direct select vs. confirmation first).
 */
@Composable
private fun FoundCaseRow(record: MessageEntity, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().rmSurface()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick
            )
            Column(Modifier.padding(8.dp)) {
                Text(
                    summaryFor(record),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "${record.messageId.take(13)}… · ${listTimeFormat.format(Date(record.createdAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = RescueColors.Muted
                )
            }
        }
    }
}

@Composable
private fun FoundFormScreen(viewModel: AppViewModel, onCreated: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val missing by viewModel.messagesOfType("MISSING_PERSON").collectAsState()
    // FIX6 cap (LOCAL only — the phone cannot know the dashboard's
    // review state offline): a case is found-linked when Room holds a
    // PERSON_FOUND_UPDATE pointing at it, own or relayed. Linked
    // cases leave the main list; the collapsed section below is the
    // only way back in, with a confirmation first.
    val foundUpdates by viewModel.messagesOfType("PERSON_FOUND_UPDATE").collectAsState()
    val linkedIds = remember(foundUpdates) { FoundLinkage.linkedCaseIds(foundUpdates) }
    val split = remember(missing, linkedIds) { FoundLinkage.split(missing, linkedIds) }
    // These two mirror FoundPickerState (domain) — the rule is tested
    // there; this screen only renders it with Compose state.
    var selectedCaseId by remember { mutableStateOf<String?>(null) }
    var pendingCaseId by remember { mutableStateOf<String?>(null) }
    var showReported by remember { mutableStateOf(false) }
    // FIX7: the human place name where the person was found — sent as
    // its own payload key (`foundArea`), mirroring a Missing report's
    // `lastSeenArea`, so the dashboard can show it. The numeric GPS
    // location below is separate and stays optional.
    var foundArea by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var location by remember { mutableStateOf<PayloadLocation?>(null) }
    var photoThumb by remember { mutableStateOf<String?>(null) }
    var photoOmitted by remember { mutableStateOf(false) }
    var fullPhoto by remember { mutableStateOf<ByteArray?>(null) }
    var photoNote by remember { mutableStateOf<String?>(null) }

    // The found person's photo rides the same pipeline as the Missing
    // form's (≤ 500 KB full photo + thumbnail), so the case's two photos
    // can later be compared side by side on the dashboard.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val picked = withContext(Dispatchers.Default) {
                    val bytes = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                    processPickedPhoto(bytes)
                }
                photoThumb = picked.thumbB64
                photoOmitted = picked.omitted
                fullPhoto = picked.fullJpeg
                photoNote = picked.note
            }
        }
    }

    FormScaffold {
        Kicker("Which missing-person case is this about?")
        if (missing.isEmpty()) {
            Text("No missing-person records are stored on this phone yet — a found update needs one to point at.")
        } else if (split.main.isEmpty()) {
            Text("Every stored case already has a found report — to send another one on purpose, open the section below.")
        }
        split.main.forEach { record ->
            FoundCaseRow(
                record,
                selected = selectedCaseId == record.messageId
            ) {
                selectedCaseId = record.messageId
                pendingCaseId = null
            }
        }
        // FIX6 override path: collapsed by default. Choosing a case
        // here does NOT select it — it stages the confirmation below.
        if (split.alreadyReported.isNotEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().rmSurface()
            ) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { showReported = !showReported }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Found report already sent (${split.alreadyReported.size})",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.weight(1f))
                        Icon(
                            if (showReported) Icons.Filled.KeyboardArrowDown else Icons.Filled.ChevronRight,
                            contentDescription = if (showReported) "Collapse" else "Expand"
                        )
                    }
                    if (showReported) {
                        Text(
                            "A second report is only for a deliberate re-report — selecting a case here asks for confirmation first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = RescueColors.Muted
                        )
                        split.alreadyReported.forEach { record ->
                            FoundCaseRow(
                                record,
                                selected = selectedCaseId == record.messageId
                            ) {
                                pendingCaseId = record.messageId
                            }
                        }
                    }
                }
            }
        }
        // FIX6 confirmation: a linked case opens the form only after
        // the user says Yes here; No (or dismiss) leaves the previous
        // selection untouched. (FIX7: the strings are English now.)
        pendingCaseId?.let { caseId ->
            AlertDialog(
                onDismissRequest = { pendingCaseId = null },
                title = { Text("Send another found report?") },
                text = { Text("A found report has already been sent for this case. Send another one?") },
                confirmButton = {
                    TextButton(onClick = { selectedCaseId = caseId; pendingCaseId = null }) {
                        Text("Yes")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingCaseId = null }) {
                        Text("No")
                    }
                }
            )
        }
        RmTextField(
            value = foundArea,
            onValueChange = { foundArea = it },
            label = "Found at — area / place name",
            singleLine = true
        )
        RmTextField(
            value = note,
            onValueChange = { note = it },
            label = "Note — when found, condition"
        )
        LocationFields(viewModel) { location = it }
        SecondaryButton(onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Attach photo of the found person", maxLines = 1, softWrap = false)
        }
        photoNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        PrimaryButton(
            onClick = {
                val caseId = selectedCaseId
                if (caseId != null) {
                    viewModel.submitPersonFound(
                        caseId, note, location,
                        photoThumbB64 = photoThumb,
                        photoOmitted = photoOmitted,
                        fullPhotoJpeg = fullPhoto,
                        foundArea = foundArea
                    ) { result ->
                        if (result is MessageCreationResult.Created) onCreated()
                    }
                }
            },
            // FIX7: the place is required, like a Missing report's
            // last-seen area — otherwise the dashboard has no location
            // to show for the found report.
            enabled = selectedCaseId != null && note.isNotBlank() && foundArea.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save found update", maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun AnnouncementFormScreen(viewModel: AppViewModel, onCreated: () -> Unit) {
    val profile by viewModel.profile.collectAsState()
    var category by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    val tomorrow = remember { Date(System.currentTimeMillis() + 24L * 60 * 60 * 1000) }
    var dateText by remember { mutableStateOf(dayFormat.format(tomorrow)) }
    var timeText by remember { mutableStateOf(hourFormat.format(tomorrow)) }
    var error by remember { mutableStateOf<String?>(null) }

    FormScaffold {
        val role = Role.fromName(profile?.role)
        if (role == Role.CITIZEN || role == Role.VOLUNTEER) {
            Text(
                "Announcements are usually posted by coordinators — yours will still be stored and relayed.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        RmTextField(
            value = category,
            onValueChange = { category = it },
            label = "Category — e.g. WATER POINT, SHELTER, ROAD",
            singleLine = true
        )
        RmTextField(
            value = text,
            onValueChange = { text = it },
            label = "Announcement text"
        )
        DateTimeFields(dateText, timeText, { dateText = it }, { timeText = it }, label = "Valid until (date / time)")
        error?.let { Text(it, color = Color(0xFFB3261E)) }
        PrimaryButton(
            onClick = {
                val validUntil = parseDateTimeMs(dateText, timeText)
                if (validUntil == null) {
                    error = "Valid-until date/time doesn't parse — use yyyy-MM-dd and HH:mm."
                } else {
                    viewModel.submitAnnouncement(category, text, validUntil) { result ->
                        if (result is MessageCreationResult.Created) onCreated()
                    }
                }
            },
            enabled = category.isNotBlank() && text.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save announcement", maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun ResourceFormScreen(viewModel: AppViewModel, onCreated: () -> Unit) {
    var resourceType by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    var location by remember { mutableStateOf<PayloadLocation?>(null) }

    FormScaffold {
        RmTextField(
            value = resourceType,
            onValueChange = { resourceType = it },
            label = "Resource — e.g. WATER, FOOD, BEDS, MEDICINE",
            singleLine = true
        )
        Stepper("Quantity available", quantity, min = 0, max = 9999) { quantity = it }
        RmTextField(
            value = note,
            onValueChange = { note = it },
            label = "Note (optional)"
        )
        LocationFields(viewModel) { location = it }
        PrimaryButton(
            onClick = {
                viewModel.submitResourceUpdate(resourceType, quantity, note.ifBlank { null }, location) { result ->
                    if (result is MessageCreationResult.Created) onCreated()
                }
            },
            enabled = resourceType.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save resource update", maxLines = 1, softWrap = false)
        }
    }
}

// ---------------------------------------------------------------- helpers

/**
 * A form's container in the rebuild: one white card (radius 22,
 * padding 14) on the warm background, fields stacked inside. The
 * form's title and back arrow live in the root's in-body back row,
 * so the scaffold itself has neither.
 */
@Composable
private fun FormScaffold(content: @Composable ColumnScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(RmSpacing.SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(RmSpacing.SECTION_GAP)
    ) {
        item {
            RmCard(content = content)
        }
    }
}

/** Single- or multi-select option chips — the preview's .fchip style (selected = RED). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRows(options: List<String>, selected: Set<String>, onToggle: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        options.forEach { option ->
            RmFilterChip(
                label = option,
                selected = option in selected,
                onClick = { onToggle(option) }
            )
        }
    }
}

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        SecondaryButton(onClick = { onChange((value - 1).coerceAtLeast(min)) }, enabled = value > min) {
            Text("−", maxLines = 1, softWrap = false)
        }
        Text(value.toString(), fontWeight = FontWeight.SemiBold)
        SecondaryButton(onClick = { onChange((value + 1).coerceAtMost(max)) }, enabled = value < max) {
            Text("+", maxLines = 1, softWrap = false)
        }
    }
}

/**
 * Location consent (OFF by default) + manual lat/lon fields + a
 * "fill from GPS" button. Coordinates are emitted as [PayloadLocation]
 * with exactly 3 decimals, source MANUAL for typed values, GPS for a
 * fresh fix and LAST_KNOWN for the saved last-known.
 *
 * FIX9 (19 Nov 2026) — the button was broken on real phones, and the
 * cause was threefold: (1) on Android 13+ the Nearby permission flow
 * never asks for location at all (its list has no location entry), so
 * the old permission check silently failed; (2) the button only ever
 * read the OS last-known fix, which is null on a fresh phone that never
 * used location, and it never tried a FRESH fix; (3) when it failed,
 * the only feedback was a small grey note. The fill now (a) asks for
 * the location permission when it is missing (same system prompt shape
 * Nearby uses) and re-runs on grant, (b) tries a fresh GPS fix first,
 * (c) falls back to the phone's saved last-known location, and (d)
 * says plainly when neither exists — it never silently does nothing.
 * The consent toggle is unchanged: the location only reaches the
 * payload when the user switched it on AND coordinates are present.
 */
@Composable
private fun LocationFields(viewModel: AppViewModel, onLocationChange: (PayloadLocation?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var consent by remember { mutableStateOf(false) }
    var latText by remember { mutableStateOf("") }
    var lonText by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(PayloadLocation.SOURCE_MANUAL) }
    var note by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }
    // Set when the permission prompt was granted and the fill must run
    // now — the launcher's callback only flips this flag; the effect
    // below runs the fill (a callback cannot call it directly, it is
    // declared after the launcher).
    var pendingFillAfterPermission by remember { mutableStateOf(false) }

    fun emit() {
        val lat = latText.toDoubleOrNull()
        val lon = lonText.toDoubleOrNull()
        onLocationChange(
            if (consent && lat != null && lon != null) PayloadLocation.of(lat, lon, source) else null
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) {
            pendingFillAfterPermission = true
        } else {
            note = "Location permission denied — allow it in Settings → Apps → RescueMesh → Permissions, or type the coordinates."
        }
    }

    fun fillFromGps() {
        if (!LocationFetcher.hasPermission(context)) {
            note = "Location permission is needed — allow it when asked, then tap Fill from GPS again."
            permissionLauncher.launch(LocationFetcher.LOCATION_PERMISSIONS)
            return
        }
        if (locating) return
        locating = true
        note = "Locating…"
        scope.launch {
            val resolved = viewModel.resolveFormLocation()
            locating = false
            if (resolved != null) {
                latText = String.format(Locale.US, "%.3f", resolved.lat)
                lonText = String.format(Locale.US, "%.3f", resolved.lon)
                source = resolved.source
                note = if (resolved.source == PayloadLocation.SOURCE_GPS) {
                    "Filled from a fresh GPS fix."
                } else {
                    "Filled from this phone's last known location."
                }
                emit()
            } else {
                note = "No location available — turn on location/GPS and try again, or type the coordinates."
            }
        }
    }

    LaunchedEffect(pendingFillAfterPermission) {
        if (pendingFillAfterPermission) {
            pendingFillAfterPermission = false
            fillFromGps()
        }
    }

    ToggleRow(
        label = "Include location",
        checked = consent,
        onCheckedChange = { on ->
            consent = on
            if (on) emit() else onLocationChange(null)
        }
    )
    if (consent) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RmTextField(
                value = latText,
                onValueChange = { latText = it; source = PayloadLocation.SOURCE_MANUAL; emit() },
                label = "Latitude",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            RmTextField(
                value = lonText,
                onValueChange = { lonText = it; source = PayloadLocation.SOURCE_MANUAL; emit() },
                label = "Longitude",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
        }
        SecondaryButton(
            onClick = { fillFromGps() },
            enabled = !locating,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (locating) "Locating…" else "Fill from GPS", maxLines = 1, softWrap = false)
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun DateTimeFields(
    dateText: String,
    timeText: String,
    onDateChange: (String) -> Unit,
    onTimeChange: (String) -> Unit,
    label: String
) {
    Kicker(label)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RmTextField(
            value = dateText,
            onValueChange = onDateChange,
            label = "Date",
            placeholder = "yyyy-MM-dd",
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        RmTextField(
            value = timeText,
            onValueChange = onTimeChange,
            label = "Time",
            placeholder = "HH:mm",
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Parses the simple date+time text fields to epoch ms; null when they don't parse. */
private fun parseDateTimeMs(dateText: String, timeText: String): Long? = runCatching {
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { isLenient = false }
        .parse("${dateText.trim()} ${timeText.trim()}")?.time
}.getOrNull()
