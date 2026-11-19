package com.rescuemesh.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rescuemesh.BuildInfo
import com.rescuemesh.crypto.DeviceIds
import com.rescuemesh.data.local.MessageEntity
import com.rescuemesh.data.local.MetricsEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The screens — LAYOUT REBUILD ROUND (15 Nov).
 *
 * The user's verdict on the earlier rounds was correct: they
 * re-skinned the OLD layout trees (fonts, colours, shadows) but never
 * rebuilt the STRUCTURE. These composables are now rebuilt
 * element-for-element from the preview
 * (`RescueMesh_UI_Redesign_Preview.html`, via
 * `ui-replica-blueprint-2026-11-15.md`): a CUSTOM bottom bar (white,
 * hairline top border, stroke icons from the preview's own SVG paths,
 * red active tab + 5 dp red dot — no M3 NavigationBar, no mint pill),
 * NO TopAppBar (tab roots carry in-body Archivo headers; pushed
 * sub-screens get the in-body [BackRow]), the preview's onboarding,
 * the Account entry hero with its two swipe boxes and the DARK
 * in-page countdown card, Home's ring-stroke SOS target and two-pill
 * status strip, and Mesh's DARK relay card.
 *
 * Every ViewModel state, callback, flow and status string is exactly
 * what the old screens used — only the layout trees changed. Where
 * the preview is silent, the existing function was placed in the
 * preview's shape and the choice is noted at the call site.
 */

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private val hourMinuteFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

// -------------------------------------------------------------------- root

/** The top title for the current router state (used by the in-body back row). */
private fun navTitle(nav: NavState): String = when (val current = nav.current) {
    is SubScreen.Form -> current.kind.title
    is SubScreen.Account -> current.page.title
    is SubScreen.PrepareList -> "Prepare — offline guides"
    is SubScreen.PrepareDetail -> "Prepare"
    null -> nav.tab.title
}

private fun tabLabel(tab: AppTab): String = when (tab) {
    AppTab.HOME -> "Home"
    AppTab.RECORDS -> "Records"
    AppTab.MESH -> "Mesh"
    AppTab.ACCOUNT -> "Account"
}

@Composable
fun RescueMeshRoot(
    viewModel: AppViewModel,
    accountViewModel: AccountViewModel,
    prepareViewModel: PrepareViewModel,
    onRequestPermissions: () -> Unit
) {
    val profile by viewModel.profile.collectAsState()
    if (profile == null) {
        OnboardingScreen(viewModel)
        return
    }
    val nav by viewModel.navState.collectAsState()

    // System Back pops a pushed sub-screen; at a tab root the handler
    // disables itself, so Back keeps its default behaviour — it never
    // switches tabs and is never swallowed (AppNav.back contract).
    BackHandler(enabled = nav.current != null) { viewModel.handleBack() }

    Column(
        modifier = Modifier.fillMaxSize().background(RescueColors.WarmBackground)
            // FORENSIC FIX ROUND (15 Nov): the previous build put a
            // plain statusBarsPadding() here and relied on targetSdk
            // enforcement to deliver the insets; on the user's phone
            // the headings still slid under the status bar. The root
            // now consumes the TOP + HORIZONTAL sides of
            // WindowInsets.safeDrawing — the same combined inset
            // source a Scaffold consumes (status bar ∪ display
            // cutout), read from the real window, never an assumed
            // height, so it holds on every device and bar size.
            // MainActivity now also calls enableEdgeToEdge()
            // explicitly, so the window state this reads is set by
            // the app, not inherited from enforcement. The background
            // is applied BEFORE the padding, so the warm colour still
            // paints behind the status bar; only content moves down.
            // The BOTTOM side is deliberately not consumed here:
            // BottomBar applies the navigation-bar inset inside its
            // own white background (see there).
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                )
            )
    ) {
        // No TopAppBar: pushed sub-screens get the preview's in-body
        // back row; tab roots carry their own in-body headers.
        if (nav.current != null) {
            BackRow(title = navTitle(nav), onBack = { viewModel.popSubScreen() })
        }
        // Hoisted above the branch: collectAsState must run on every
        // composition, not only inside one `when` arm.
        val prepareSavedIds by prepareViewModel.savedIds.collectAsState()
        Box(modifier = Modifier.weight(1f)) {
            // Prepare hub (Prompt 10): pushed over the Home tab from
            // its card. It renders here, above the tab switch, so the
            // bottom bar (4 tabs, unchanged) stays visible underneath
            // and Back pops back to Home.
            when (val current = nav.current) {
                is SubScreen.PrepareList -> PrepareListScreen(viewModel, prepareViewModel)
                is SubScreen.PrepareDetail -> {
                    val guide = com.rescuemesh.domain.PrepareGuides.byId(current.guideId)
                    if (guide != null) {
                        PrepareDetailScreen(
                            guide = guide,
                            saved = guide.id in prepareSavedIds,
                            onToggleSaved = { prepareViewModel.toggleSaved(guide.id) }
                        )
                    } else {
                        PrepareListScreen(viewModel, prepareViewModel)
                    }
                }
                else -> when (nav.tab) {
                    AppTab.HOME -> HomeScreen(viewModel, accountViewModel)
                    AppTab.RECORDS -> RecordsScreen(viewModel)
                    AppTab.MESH -> MeshScreen(viewModel, onRequestPermissions)
                    AppTab.ACCOUNT -> AccountTab(viewModel, accountViewModel)
                }
            }
        }
        BottomBar(current = nav.tab, onSelect = { viewModel.selectTab(it) })
    }
}

/**
 * The preview's CUSTOM bottom bar (NOT M3 NavigationBar): white,
 * a hairline #EDE7DF top border, 21 dp stroke icons drawn from the
 * preview HTML's exact SVG paths ([RmNavIcon]), 10.5 sp ExtraBold
 * labels; inactive #9AA0A8, active = Rescue Red icon + label with a
 * 5 dp red DOT under the label (no mint pill anywhere).
 */
@Composable
private fun BottomBar(current: AppTab, onSelect: (AppTab) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(Color.White)
            // RESPONSIVE (15 Nov): the system navigation-bar inset is
            // applied INSIDE the bar's own white background — the
            // white still paints behind the gesture/3-button area,
            // but the icons/labels lift clear of it by however tall
            // THIS device's nav bar is (0–48 dp across devices; never
            // an assumed height). Background first, then the inset
            // padding, then the content.
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Box(
            Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEDE7DF))
        )
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            AppTab.values().forEach { tab ->
                val active = tab == current
                val color = if (active) RescueColors.RescueRed else Color(0xFF9AA0A8)
                Column(
                    modifier = Modifier.weight(1f)
                        .clickable { onSelect(tab) }
                        .padding(top = 2.dp, bottom = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    RmNavIcon(tab, color)
                    Text(
                        tabLabel(tab),
                        color = color,
                        style = TextStyle(
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 10.5.sp
                        ),
                        maxLines = 1,
                        softWrap = false
                    )
                    Box(
                        Modifier.size(5.dp).clip(CircleShape)
                            .background(if (active) RescueColors.RescueRed else Color.Transparent)
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------- onboarding

/**
 * Onboarding per the preview: gradient hero (brand row + headline +
 * paragraph), the "SET UP YOUR PROFILE" kicker, an [RmTextField],
 * the amber CITIZEN info card, the btn-red "Continue", the centred
 * footnote and the dots. Still NO role picker — the save is exactly
 * `saveProfile(displayNameOrDefault(name), DEFAULT_ROLE)` as before.
 */
@Composable
fun OnboardingScreen(viewModel: AppViewModel) {
    var name by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize()
            .background(RescueColors.WarmBackground)
            // Same safe-drawing inset as the main root (onboarding
            // is a separate root while no profile exists) — see
            // RescueMeshRoot for the forensic note.
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                )
            )
            .padding(RmSpacing.SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(RmSpacing.SECTION_GAP)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFFDFF5EA), Color(0xFFBDEED6))
                    )
                )
                .padding(horizontal = 16.dp, vertical = 18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier.size(32.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(RescueColors.RescueRed),
                        contentAlignment = Alignment.Center
                    ) {
                        // The preview's brand mark: a near-full arc +
                        // arrow head, stroke white 2.4 (its SVG paths
                        // `M20.8 8.6a9 9 0 1 0 1.6 5` + `M22 3v5h-5`).
                        StrokeGlyph(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.4f
                        ) {
                            drawArc(
                                color = Color.White,
                                startAngle = -45f,
                                sweepAngle = -280f,
                                useCenter = false,
                                topLeft = Offset(3f, 3f),
                                size = Size(18f, 18f),
                                style = Stroke(width = 2.4f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            )
                            val head = Path().apply {
                                moveTo(22f, 3f)
                                lineTo(22f, 8f)
                                lineTo(17f, 8f)
                            }
                            drawPath(
                                head, Color.White,
                                style = Stroke(
                                    width = 2.4f,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                    join = androidx.compose.ui.graphics.StrokeJoin.Round
                                )
                            )
                        }
                    }
                    Text(
                        "RescueMesh",
                        style = TextStyle(
                            fontFamily = ArchivoFamily,
                            fontWeight = FontWeight.Black,
                            fontSize = 17.sp,
                            color = RescueColors.Ink
                        )
                    )
                }
                Text(
                    "Help finds you,\neven offline.",
                    style = TextStyle(
                        fontFamily = ArchivoFamily,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 21.sp,
                        lineHeight = 26.sp,
                        color = RescueColors.Ink
                    )
                )
                Text(
                    "Emergency messages hop phone-to-phone over the mesh — no network, no tower needed.",
                    style = TextStyle(
                        fontFamily = ManropeFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        color = Color(0xFF33523F)
                    )
                )
            }
        }
        Kicker("Set up your profile")
        RmTextField(
            value = name,
            onValueChange = { name = it },
            label = "Your name",
            placeholder = "Your name",
            singleLine = true
        )
        // Amber info card (preview .info-line). The preview's own
        // wording is shown; the save still uses OnboardingPolicy.
        Box(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFFFF7E8))
                .border(1.dp, Color(0xFFF5E3BC), RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StrokeGlyph(
                    modifier = Modifier.size(18.dp),
                    color = Color(0xFFB45309),
                    strokeWidth = 2f
                ) {
                    drawCircle(
                        Color(0xFFB45309), radius = 9f, center = Offset(12f, 12f),
                        style = Stroke(width = 2f)
                    )
                    drawLine(
                        Color(0xFFB45309), Offset(12f, 11f), Offset(12f, 16f),
                        strokeWidth = 2f, cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                    drawCircle(Color(0xFFB45309), radius = 1.1f, center = Offset(12f, 8f))
                }
                Text(
                    buildAnnotatedString {
                        append("You start as a ")
                        withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append("CITIZEN") }
                        append(
                            ". Volunteer & Coordinator roles are granted later from the " +
                                "Account tab, after admin review — there is no role picker here."
                        )
                    },
                    style = TextStyle(
                        fontFamily = ManropeFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = Color(0xFF6B5410)
                    )
                )
            }
        }
        PrimaryButton(
            onClick = {
                viewModel.saveProfile(
                    OnboardingPolicy.displayNameOrDefault(name),
                    OnboardingPolicy.DEFAULT_ROLE
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue", maxLines = 1, softWrap = false)
        }
        Text(
            "SOS & I Am Safe work without any account.",
            style = TextStyle(
                fontFamily = ManropeFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.5.sp,
                color = RescueColors.Muted
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.width(20.dp).height(7.dp).clip(CircleShape)
                    .background(RescueColors.RescueRed)
            )
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFD8D2C8))
            )
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFD8D2C8))
            )
        }
    }
}

// -------------------------------------------------------------------- home

/**
 * Home per the preview: header ROW (kicker + UPPERCASE name left,
 * role chip right), the red SOS hero with the RING-STROKE target,
 * the green I Am Safe card with its circular icon badge, the
 * TWO-PILL status strip, and mini-records WITH 40 dp thumbs. The
 * device-id line is gone from here (it lives as meta in Account).
 * Interactions unchanged: SOS tap 1 arms / tap 2 sends; I Am Safe is
 * one tap; pill 1 tap → Mesh tab; a mini-record tap → Records tab.
 */
@Composable
fun HomeScreen(viewModel: AppViewModel, accountViewModel: AccountViewModel) {
    val profile by viewModel.profile.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val status by viewModel.statusMessage.collectAsState()
    val relayOn by viewModel.relayOn.collectAsState()
    val peers by viewModel.livePeers.collectAsState()
    val syncStatus by accountViewModel.syncStatus.collectAsState()
    var sosArmed by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(RmSpacing.SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(RmSpacing.SECTION_GAP)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Kicker("Welcome back,")
                    Text(
                        (profile?.displayName ?: "").uppercase(),
                        style = TextStyle(
                            fontFamily = ArchivoFamily,
                            fontWeight = FontWeight.Black,
                            fontSize = 25.sp,
                            color = RescueColors.Ink
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(modifier = Modifier.padding(top = 6.dp)) {
                    RoleChip(profile?.role ?: "")
                }
            }
        }
        item {
            // SOS hero: red card, ring-stroke target on the right.
            Box(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(RescueColors.RescueRed)
                    .clickable {
                        if (sosArmed) {
                            viewModel.sendSos()
                            sosArmed = false
                        } else {
                            sosArmed = true
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Kicker("Active SOS", color = Color.White.copy(alpha = 0.75f))
                        Text(
                            if (sosArmed) "TAP AGAIN\nTO SEND" else "TAP FOR\nHELP",
                            style = TextStyle(
                                fontFamily = ArchivoFamily,
                                fontWeight = FontWeight.Black,
                                fontSize = 22.sp,
                                lineHeight = 27.sp,
                                color = Color.White
                            )
                        )
                        Text(
                            if (sosArmed) {
                                "Tap the SOS button once more to save the SOS on this phone."
                            } else {
                                "Tap once to arm, again to send. Works fully offline."
                            },
                            style = TextStyle(
                                fontFamily = ManropeFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp,
                                lineHeight = 15.sp,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        )
                    }
                    SosTargetMotif()
                }
            }
        }
        item {
            // I Am Safe: green card, 42 dp circular badge with the
            // shield-check glyph, title + subtitle. Exactly one tap.
            Box(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(RescueColors.SafeGreen)
                    .clickable { viewModel.sendIAmSafe() }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier.size(42.dp).clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        StrokeGlyph(
                            modifier = Modifier.size(21.dp),
                            color = Color.White,
                            strokeWidth = 2.3f
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
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            "I AM SAFE",
                            style = TextStyle(
                                fontFamily = ArchivoFamily,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 17.sp,
                                color = Color.White
                            )
                        )
                        Text(
                            "One tap — lets the mesh know you are okay.",
                            style = TextStyle(
                                fontFamily = ManropeFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.5.sp,
                                color = Color.White.copy(alpha = 0.88f)
                            )
                        )
                    }
                }
            }
        }
        item {
            // Two-pill status strip (preview .status-strip).
            val syncPill = syncPillFor(syncStatus)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(
                    modifier = Modifier.weight(1f)
                        .rmSurface(RoundedCornerShape(14.dp))
                        .clickable { viewModel.selectTab(AppTab.MESH) }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Box(
                            Modifier.size(8.dp).clip(CircleShape)
                                .background(
                                    if (relayOn) RescueColors.SafeGreen else RescueColors.Muted
                                )
                        )
                        Column {
                            Text(
                                if (relayOn) "Relay ON" else "Relay OFF",
                                style = TextStyle(
                                    fontFamily = ManropeFamily,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 11.sp,
                                    color = RescueColors.Ink
                                )
                            )
                            Text(
                                if (relayOn) {
                                    "${peers.size} peers nearby"
                                } else {
                                    "Start it from the Mesh tab"
                                },
                                style = TextStyle(
                                    fontFamily = ManropeFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    color = RescueColors.Muted
                                )
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier.weight(1f)
                        .rmSurface(RoundedCornerShape(14.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        StrokeGlyph(
                            modifier = Modifier.size(15.dp),
                            color = RescueColors.SafeGreen,
                            strokeWidth = 2.6f
                        ) {
                            val check = Path().apply {
                                moveTo(4f, 12.5f)
                                lineTo(9.5f, 18f)
                                lineTo(20f, 6.5f)
                            }
                            drawPath(
                                check, RescueColors.SafeGreen,
                                style = Stroke(
                                    width = 2.6f,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                    join = androidx.compose.ui.graphics.StrokeJoin.Round
                                )
                            )
                        }
                        Column {
                            Text(
                                syncPill.first,
                                style = TextStyle(
                                    fontFamily = ManropeFamily,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 11.sp,
                                    color = RescueColors.Ink
                                )
                            )
                            Text(
                                syncPill.second,
                                style = TextStyle(
                                    fontFamily = ManropeFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    color = RescueColors.Muted
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
        // The shared status line (preview silent — kept from the old
        // Home, under the strip): every send / refusal reports here.
        status?.let { text ->
            item {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        // Prepare hub (Prompt 10): the ONLY entry point. A card on
        // Home that pushes the guide list — no 5th bottom-bar tab,
        // no login, no network. The guides are bundled in the app.
        item {
            RmCard(onClick = { viewModel.pushSubScreen(SubScreen.PrepareList) }) {
                Kicker("Prepare — offline guides")
                Text(
                    "Be ready before a disaster — flood, earthquake, cyclone, fire, landslide, first aid, go-bag and family plan.",
                    style = TextStyle(
                        fontFamily = ManropeFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        color = RescueColors.Muted
                    )
                )
                Text(
                    "9 guides · Works in airplane mode ›",
                    style = TextStyle(
                        fontFamily = ManropeFamily,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp,
                        color = RescueColors.Ink
                    )
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Kicker("Latest records")
                Spacer(Modifier.weight(1f))
                Text(
                    "Saved on this phone (${messages.size})",
                    style = TextStyle(
                        fontFamily = ManropeFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.5.sp,
                        color = RescueColors.Muted
                    )
                )
            }
        }
        items(messages.take(3)) { message ->
            MiniRecordCard(viewModel, message) { viewModel.selectTab(AppTab.RECORDS) }
        }
        if (messages.isEmpty()) {
            item {
                Text(
                    "No records yet — your SOS, safety check-ins and form reports will show up here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RescueColors.Muted
                )
            }
        }
    }
}

/**
 * Maps the gateway-sync status line onto the preview's second pill
 * ("Synced / Server up to date"). The full status string stays
 * visible in Account → Server & sync; the pill shows the short form,
 * or the raw status when it says something the short forms don't
 * cover (a stopped sync, a login-needed note).
 */
private fun syncPillFor(syncStatus: String): Pair<String, String> = when {
    syncStatus.startsWith("Nothing to sync") -> "Synced" to "Server up to date"
    syncStatus.startsWith("Synced:") -> "Synced" to syncStatus
    syncStatus.startsWith("Not synced") -> "Not synced" to "Sync from the Account tab"
    else -> "Gateway sync" to syncStatus
}

/**
 * The SOS hero's target, drawn at the preview's exact geometry
 * (80 dp): outer ring stroke 3 dp white .45, inner ring (inset
 * 13 dp) stroke 3 dp white .55, a white centre disc (inset 27 dp),
 * and a 13 dp Rescue Red core. Ring STROKES — not the filled
 * translucent discs the earlier rounds drew.
 */
@Composable
private fun SosTargetMotif() {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(80.dp)) {
        val r = size.minDimension / 2
        val stroke = 3.dp.toPx()
        drawCircle(
            color = Color.White.copy(alpha = 0.45f),
            radius = r - stroke / 2,
            style = Stroke(width = stroke)
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.55f),
            radius = r - 13.dp.toPx() - stroke / 2,
            style = Stroke(width = stroke)
        )
        drawCircle(color = Color.White, radius = 13.dp.toPx())
        drawCircle(color = RescueColors.RescueRed, radius = 6.5.dp.toPx())
    }
}

/**
 * A compact record row for Home's latest-3 (preview .mini-rec):
 * white, radius 18, state-coloured left border, a 40 dp thumb
 * (photo, or the type tile from [RecordTile]), title + "type · date,
 * time" meta, and the state chip at the right.
 */
@Composable
private fun MiniRecordCard(
    viewModel: AppViewModel,
    record: MessageEntity,
    onClick: () -> Unit
) {
    val photoState = rememberRecordPhotoState(viewModel, record)
    Box(
        modifier = Modifier.fillMaxWidth()
            .rmSurface(RoundedCornerShape(18.dp))
            .clickable { onClick() }
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            StateBorder(record.localState)
            Row(
                modifier = Modifier.weight(1f)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                RecordTile(record, photoState, size = 40.dp, allowDialog = false)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Text(
                        summaryFor(record),
                        style = TextStyle(
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.5.sp,
                            color = RescueColors.Ink
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${recordTypeLabel(record.eventType)} · " +
                            homeTimeFormat.format(Date(record.createdAt)),
                        style = TextStyle(
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            color = RescueColors.Muted
                        )
                    )
                    // Prompt 10 Emergency screen (surfacing only): the
                    // record's state in plain words, under its type
                    // line. The states already exist — this only
                    // translates them; nothing new is introduced.
                    Text(
                        com.rescuemesh.domain.PrepareGuides.statusLineFor(record.localState),
                        style = TextStyle(
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = RescueColors.Muted
                        )
                    )
                }
                StateChip(record.localState)
            }
        }
    }
}

// -------------------------------------------------------------------- mesh

/**
 * Mesh per the preview: in-body header, the DARK relay card (ink
 * background, white text, green-track switch, the advertising strip
 * inside it), the controls as btn-ghost pairs + TTL ToggleRow +
 * Export ghost button, peers as CHIPS (tap selects → that peer's
 * Connect / Send-test ghost button below), message rows (received
 * first, then the metrics under their own kicker — no data dropped),
 * and the 3-box Sent / Relayed / Dropped strip, counted by
 * [MeshCounts] from the real metrics rows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MeshScreen(viewModel: AppViewModel, onRequestPermissions: () -> Unit) {
    val profile by viewModel.profile.collectAsState()
    val peers by viewModel.livePeers.collectAsState()
    val running by viewModel.meshRunning.collectAsState()
    val received by viewModel.receivedTexts.collectAsState()
    val lastSend by viewModel.lastSendInfo.collectAsState()
    val metrics by viewModel.recentMetrics.collectAsState()
    val counts by viewModel.meshCounts.collectAsState()
    val status by viewModel.statusMessage.collectAsState()
    val relayOn by viewModel.relayOn.collectAsState()
    val ttlTestMode by viewModel.ttlTestMode.collectAsState()
    var selectedPeerId by remember { mutableStateOf<String?>(null) }
    var expandedReceived by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var expandedMetrics by remember { mutableStateOf<Set<Long>>(emptySet()) }

    // 8038 fix: when a Start mesh / Relay ON tap is blocked by missing
    // permissions, the ViewModel emits here — show the system
    // permission prompt right away. After granting, the user taps
    // again to start (no auto-retry machinery).
    LaunchedEffect(Unit) {
        viewModel.permissionRequests.collect { onRequestPermissions() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(RmSpacing.SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(RmSpacing.SECTION_GAP)
    ) {
        item {
            ScreenHeader("Mesh Relay", "Store-and-forward over Nearby Connections.")
        }
        status?.let { text ->
            item {
                RmCard {
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            // The DARK relay card (preview .relay-card): ink bg,
            // Archivo title + muted sub, the switch with a green
            // track, and the .adv strip inside the same card.
            val relaySub = when {
                running -> "Discovering & advertising"
                relayOn -> "Relay service running"
                else -> "Relay is off"
            }
            Box(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(RescueColors.Ink)
                    .padding(horizontal = 16.dp, vertical = 15.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                if (relayOn) "Relay is ON" else "Relay is OFF",
                                style = TextStyle(
                                    fontFamily = ArchivoFamily,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.5.sp,
                                    color = Color.White
                                )
                            )
                            Text(
                                relaySub,
                                style = TextStyle(
                                    fontFamily = ManropeFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp,
                                    color = Color(0xFFB9BEC7)
                                )
                            )
                        }
                        Switch(
                            checked = relayOn,
                            onCheckedChange = { viewModel.setRelayEnabled(it) },
                            colors = DarkSwitchColors
                        )
                    }
                    Spacer(Modifier.height(11.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.09f))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            buildAnnotatedString {
                                append("Advertising as ")
                                withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) {
                                    append(
                                        profile?.let {
                                            DeviceIds.advertisedLabel(it.deviceId)
                                        } ?: "—"
                                    )
                                }
                                append(" · Gateway sync ready when internet returns")
                            },
                            style = TextStyle(
                                fontFamily = ManropeFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = Color(0xFFD8DCE2)
                            )
                        )
                    }
                }
            }
        }
        item {
            // Controls — every old action, in the preview's language:
            // the airplane hint, a btn-ghost pair, the TTL ToggleRow,
            // the Export ghost button, the last-send line.
            RmCard {
                Text(
                    "Airplane mode ON, Wi-Fi and Bluetooth ON, on both phones. Then start the mesh on both.",
                    style = TextStyle(
                        fontFamily = ManropeFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.5.sp,
                        color = RescueColors.Muted
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(RmSpacing.ROW_GAP)
                ) {
                    SecondaryButton(
                        onClick = onRequestPermissions,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Permissions", maxLines = 1, softWrap = false)
                    }
                    if (running) {
                        SecondaryButton(
                            onClick = { viewModel.stopMesh() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Stop mesh", maxLines = 1, softWrap = false)
                        }
                    } else {
                        SecondaryButton(
                            onClick = { viewModel.startMesh() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Start mesh", maxLines = 1, softWrap = false)
                        }
                    }
                }
                ToggleRow(
                    label = "TTL test mode (expire after 60 s)",
                    checked = ttlTestMode,
                    onCheckedChange = { viewModel.setTtlTestMode(it) }
                )
                SecondaryButton(
                    onClick = { viewModel.exportMetricsCsv() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Export metrics CSV", maxLines = 1, softWrap = false)
                }
                lastSend?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        item { Kicker("Peers nearby · ${peers.size}") }
        item {
            if (peers.isEmpty()) {
                Text(
                    "No peers yet — start the mesh on both phones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RescueColors.Muted
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    peers.forEach { peer ->
                        PeerChip(
                            name = peer.deviceName,
                            connected = peer.connected,
                            selected = peer.endpointId == selectedPeerId,
                            onClick = {
                                selectedPeerId =
                                    if (peer.endpointId == selectedPeerId) null
                                    else peer.endpointId
                            }
                        )
                    }
                }
            }
        }
        // The selected peer's action, as a btn-ghost row under the
        // chips (the preview's chip-tap pattern; the old per-peer
        // cards' Connect / Send-test actions, unchanged).
        val selectedPeer = peers.firstOrNull { it.endpointId == selectedPeerId }
        if (selectedPeer != null) {
            item {
                if (!selectedPeer.connected) {
                    SecondaryButton(
                        onClick = { viewModel.connectPeer(selectedPeer.endpointId) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Connect to ${selectedPeer.deviceName}",
                            maxLines = 1, softWrap = false
                        )
                    }
                } else {
                    SecondaryButton(
                        onClick = { viewModel.sendTestPayload(selectedPeer.endpointId) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Send test message", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        item { Kicker("Messages") }
        if (received.isEmpty()) {
            item {
                Text(
                    "Nothing received yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = RescueColors.Muted
                )
            }
        }
        items(received.size) { index ->
            ReceivedRow(
                text = received[index],
                expanded = index in expandedReceived,
                onToggle = {
                    expandedReceived =
                        if (index in expandedReceived) expandedReceived - index
                        else expandedReceived + index
                }
            )
        }
        item { Kicker("Recent metrics") }
        items(metrics) { metric ->
            MetricRow(
                metric = metric,
                expanded = metric.id in expandedMetrics,
                onToggle = {
                    expandedMetrics =
                        if (metric.id in expandedMetrics) expandedMetrics - metric.id
                        else expandedMetrics + metric.id
                }
            )
        }
        item {
            // The 3-box metrics strip (preview .metrics), counted from
            // ALL metrics rows by MeshCounts — see its mapping doc.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                MetricBox(counts.sent, "Sent", Modifier.weight(1f))
                MetricBox(counts.relayed, "Relayed", Modifier.weight(1f))
                MetricBox(counts.dropped, "Dropped", Modifier.weight(1f))
            }
        }
    }
}

/** One peer as a preview chip: white pill, state dot, name + status. Tap selects it. */
@Composable
private fun PeerChip(
    name: String,
    connected: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    androidx.compose.material3.Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable { onClick() },
        shape = RoundedCornerShape(50),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp, if (selected) RescueColors.RescueRed else RmBorder
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                Modifier.size(7.dp).clip(CircleShape)
                    .background(
                        if (connected) RescueColors.SafeGreen else Color(0xFFB45309)
                    )
            )
            Text(
                name,
                style = TextStyle(
                    fontFamily = ManropeFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 11.sp,
                    color = RescueColors.Ink
                ),
                maxLines = 1,
                softWrap = false
            )
            Text(
                if (connected) "Connected" else "Discovered",
                style = TextStyle(
                    fontFamily = ManropeFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    color = RescueColors.Muted
                ),
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * One received payload as a preview message row (radius 16, slate
 * left edge — received texts carry no record state): a one-line
 * preview, expanding to the full text under a dashed divider. The
 * old screen's 90-character flattened preview rule is kept.
 */
@Composable
private fun ReceivedRow(text: String, expanded: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .rmSurface(RoundedCornerShape(16.dp))
            .clickable { onToggle() }
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            StateBorder("CREATED")
            Column(
                modifier = Modifier.weight(1f)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "RECEIVED",
                        style = TextStyle(
                            fontFamily = ArchivoFamily,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            letterSpacing = 0.03.em,
                            color = RescueColors.Ink
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.ChevronRight,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = Color(0xFF9AA0A8),
                        modifier = Modifier.size(16.dp)
                    )
                }
                if (expanded) {
                    DashedDivider()
                    Text(
                        text,
                        style = TextStyle(
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            lineHeight = 17.sp,
                            color = Color(0xFF3D434C)
                        )
                    )
                } else {
                    Text(
                        text.replace("\n", " ").take(90) +
                            if (text.length > 90) "…" else "",
                        style = TextStyle(
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.5.sp,
                            color = RescueColors.Muted
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * One metrics row in the message-row shape: event name in Archivo,
 * an OK / FAILED chip (the shared [StatusChip] with the PEER_ACKED /
 * REJECTED colour pair), the time at the right, the detail as the
 * summary line, and the full row (detail / message / peer / at /
 * hops) as bold-label detail lines when expanded. No field of the
 * old expandable metric card is dropped.
 */
@Composable
private fun MetricRow(metric: MetricsEntity, expanded: Boolean, onToggle: () -> Unit) {
    val chipColors = StateColors.forState(if (metric.success) "PEER_ACKED" else "REJECTED")
    Box(
        modifier = Modifier.fillMaxWidth()
            .rmSurface(RoundedCornerShape(16.dp))
            .clickable { onToggle() }
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            StateBorder(if (metric.success) "PEER_ACKED" else "REJECTED")
            Column(
                modifier = Modifier.weight(1f)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        metric.event,
                        style = TextStyle(
                            fontFamily = ArchivoFamily,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            letterSpacing = 0.03.em,
                            color = RescueColors.Ink
                        )
                    )
                    StatusChip(if (metric.success) "OK" else "FAILED", chipColors)
                    Spacer(Modifier.weight(1f))
                    Text(
                        hourMinuteFormat.format(Date(metric.startedAt)),
                        style = TextStyle(
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.5.sp,
                            color = RescueColors.Muted
                        )
                    )
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.ChevronRight,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = Color(0xFF9AA0A8),
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    metric.detail ?: "${metric.event} · ${metric.durationMs} ms",
                    style = TextStyle(
                        fontFamily = ManropeFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.5.sp,
                        color = RescueColors.Muted
                    ),
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (expanded) {
                    DashedDivider()
                    DetailLine("Duration", "${metric.durationMs} ms")
                    DetailLine("Message", metric.messageId ?: "—")
                    DetailLine("Peer", metric.peerId ?: "—")
                    DetailLine("At", timeFormat.format(Date(metric.startedAt)))
                    DetailLine("Hops", metric.hopCount.toString())
                }
            }
        }
    }
}

/** One bold-label detail line of an expanded Mesh row (preview .msg-detail style). */
@Composable
private fun DetailLine(label: String, value: String) {
    Row {
        Text(
            "$label: ",
            style = TextStyle(
                fontFamily = ManropeFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.sp,
                color = RescueColors.Ink
            )
        )
        Text(
            value,
            style = TextStyle(
                fontFamily = ManropeFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                color = Color(0xFF3D434C)
            )
        )
    }
}

/** One box of the Mesh metrics strip: Archivo Black number, small-caps label. */
@Composable
private fun MetricBox(value: Int, label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .rmSurface(RoundedCornerShape(14.dp))
            .padding(horizontal = 6.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                value.toString(),
                style = TextStyle(
                    fontFamily = ArchivoFamily,
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp,
                    color = RescueColors.Ink
                )
            )
            Text(
                label.uppercase(),
                style = TextStyle(
                    fontFamily = ManropeFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 10.sp,
                    letterSpacing = 0.06.em,
                    color = RescueColors.Muted
                )
            )
        }
    }
}

// ----------------------------------------------------------------- account

/**
 * The Account tab: the entry landing plus its pushed sub-pages.
 * Every action and status string of the old Account screen survives —
 * reorganised into the preview's landing, cards and pages. Nothing
 * here gates anything: SOS and the mesh never look at this tab.
 */
@Composable
fun AccountTab(viewModel: AppViewModel, accountViewModel: AccountViewModel) {
    val nav by viewModel.navState.collectAsState()
    val session by accountViewModel.session.collectAsState()
    val pending by accountViewModel.pendingVerificationUsername.collectAsState()

    // The old screen moved the user straight to the code-entry step
    // when a signup or an unverified login set a pending verification —
    // same move here, as a pushed page.
    LaunchedEffect(pending) {
        val page = (nav.current as? SubScreen.Account)?.page
        if (pending != null && nav.tab == AppTab.ACCOUNT &&
            (page == AccountPage.LOGIN || page == AccountPage.SIGN_UP)
        ) {
            viewModel.pushSubScreen(SubScreen.Account(AccountPage.VERIFY))
        }
    }
    // A successful sign-in (login, verify, redeem, startup refresh)
    // lands back on the landing, where the session card shows.
    LaunchedEffect(session) {
        if (session != null && nav.tab == AppTab.ACCOUNT && nav.current is SubScreen.Account) {
            viewModel.popToTabRoot()
        }
    }

    when (val current = nav.current) {
        is SubScreen.Account -> when (current.page) {
            AccountPage.LOGIN -> LoginPage(
                accountViewModel,
                onOpenSignUp = {
                    viewModel.popSubScreen()
                    viewModel.pushSubScreen(SubScreen.Account(AccountPage.SIGN_UP))
                }
            )
            AccountPage.SIGN_UP -> SignUpPage(
                accountViewModel,
                onOpenLogin = {
                    viewModel.popSubScreen()
                    viewModel.pushSubScreen(SubScreen.Account(AccountPage.LOGIN))
                }
            )
            AccountPage.VERIFY -> VerifyPage(accountViewModel)
            AccountPage.ROLE -> RolePage(accountViewModel)
            AccountPage.SERVER -> ServerPage(accountViewModel)
        }
        else -> AccountLanding(viewModel, accountViewModel)
    }
}

/** Status / error / busy lines of the account layer, shown above every Account page. */
@Composable
private fun AccountStatusStrip(accountViewModel: AccountViewModel) {
    val status by accountViewModel.statusMessage.collectAsState()
    val error by accountViewModel.errorMessage.collectAsState()
    val busy by accountViewModel.busy.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        status?.let { Text(it) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) Text("Working…")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountLanding(viewModel: AppViewModel, accountViewModel: AccountViewModel) {
    val profile by viewModel.profile.collectAsState()
    val session by accountViewModel.session.collectAsState()
    val pending by accountViewModel.pendingVerificationUsername.collectAsState()
    val syncStatus by accountViewModel.syncStatus.collectAsState()
    val serverUrl by accountViewModel.serverUrl.collectAsState()
    val isDefaultServer by accountViewModel.isUsingDefaultServer.collectAsState()
    val countdownEndsAt by viewModel.sosCountdownEndsAtMs.collectAsState()
    val me = session

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(RmSpacing.SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(RmSpacing.SECTION_GAP)
    ) {
        item { AccountStatusStrip(accountViewModel) }
        item {
            // The entry hero (preview .entry-hero): gradient, the
            // time-based greeting, the name, the role + login chips,
            // and the two swipe boxes INSIDE the hero. Gestures are
            // exactly as before: swipe LEFT → the login page; swipe
            // RIGHT → the 3-second SOS countdown. The boxes are
            // tappable too — gestures are a shortcut, never a gate.
            Box(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(0xFFCFF3DF),
                                Color(0xFF9FE3C0),
                                Color(0xFF7ED6AC)
                            )
                        )
                    )
                    .pointerInput(Unit) {
                        var totalDrag = 0f
                        val thresholdPx = 120.dp.toPx()
                        detectHorizontalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                totalDrag += dragAmount
                                change.consume()
                            },
                            onDragEnd = {
                                when (classifySwipe(totalDrag, thresholdPx)) {
                                    SwipeDirection.LEFT ->
                                        viewModel.pushSubScreen(
                                            SubScreen.Account(AccountPage.LOGIN)
                                        )
                                    SwipeDirection.RIGHT -> viewModel.startSosCountdown()
                                    SwipeDirection.NONE -> Unit
                                }
                                totalDrag = 0f
                            },
                            onDragCancel = { totalDrag = 0f }
                        )
                    }
                    .padding(start = 16.dp, end = 16.dp, top = 15.dp, bottom = 13.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        greetingForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)),
                        style = TextStyle(
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp,
                            color = Color(0xFF20503B)
                        )
                    )
                    Text(
                        profile?.displayName ?: "",
                        style = TextStyle(
                            fontFamily = ArchivoFamily,
                            fontWeight = FontWeight.Black,
                            fontSize = 24.sp,
                            color = RescueColors.Ink
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // RESPONSIVE (15 Nov): FlowRow, not Row — at a
                    // compact width (~320 dp) with font scale 1.3 the
                    // role chip + login chip together are wider than
                    // the hero's inner width, and a Row would push
                    // the second chip off the edge; a FlowRow wraps
                    // it underneath instead. Identical at standard
                    // widths/scales, where both chips fit on one line.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        RoleChip(profile?.role ?: "")
                        if (me != null) {
                            RoleChip(
                                me.username,
                                background = Color.White.copy(alpha = 0.75f),
                                textColor = Color(0xFF20503B)
                            )
                        } else {
                            RoleChip(
                                "Not logged in",
                                background = Color.White.copy(alpha = 0.75f),
                                textColor = Color(0xFF5B4A08),
                                dotColor = Color(0xFFB45309)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SwipeBox(
                            title = "← Swipe left",
                            body = "Login / Sign up for an official record",
                            modifier = Modifier.weight(1f)
                        ) {
                            viewModel.pushSubScreen(SubScreen.Account(AccountPage.LOGIN))
                        }
                        SwipeBox(
                            title = "Swipe right →",
                            body = "Emergency SOS with a 3-second countdown",
                            modifier = Modifier.weight(1f)
                        ) {
                            viewModel.startSosCountdown()
                        }
                    }
                }
            }
        }
        // The countdown as the preview's DARK IN-PAGE CARD (the old
        // full-screen red overlay is gone): red circle numeral +
        // text; tapping cancels; on zero the ViewModel fires the
        // existing sendSos() and lands on Home.
        if (countdownEndsAt != null) {
            item {
                CountdownCard(viewModel, countdownEndsAt!!)
            }
        }
        if (pending != null) {
            item {
                RmCard(spacing = 8.dp) {
                    Text(
                        "Email verification pending",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Enter the 6-digit code sent for $pending.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    PrimaryButton(
                        onClick = {
                            viewModel.pushSubScreen(SubScreen.Account(AccountPage.VERIFY))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Enter verification code", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        if (me == null) {
            item {
                // The preview's server-account card (logged out).
                RmCard {
                    Kicker("Server account · optional")
                    Text(
                        "An account is only for the official record — verify email, apply for a role, redeem an access code.",
                        style = TextStyle(
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp,
                            color = RescueColors.Muted
                        )
                    )
                    // FIX5 (19 Nov): a fresh install already points at
                    // the Render default, so login needs no setup — the
                    // line below just says which server is in use.
                    // (FIX4: the Server & sync row below this card is
                    // reachable logged out, so a custom URL can still
                    // be saved before login when testing.)
                    Text(
                        if (isDefaultServer) {
                            "Server: $serverUrl (default)"
                        } else {
                            "Server: $serverUrl (custom)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = RescueColors.Muted
                    )
                    PrimaryButton(
                        onClick = {
                            viewModel.pushSubScreen(SubScreen.Account(AccountPage.LOGIN))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Login / Sign up", maxLines = 1, softWrap = false)
                    }
                    // The device id as quiet meta (it moved here from
                    // Home, per the blueprint) — plus the BUILD TAG:
                    // this line is the proof instrument that shows
                    // which delivered build is running (see BuildInfo).
                    Text(
                        "Device: ${profile?.deviceId?.take(13) ?: ""}… · Build ${BuildInfo.TAG}",
                        style = MaterialTheme.typography.bodySmall,
                        color = RescueColors.Muted
                    )
                }
            }
        } else {
            item {
                RmCard(spacing = 8.dp) {
                    Kicker("Signed in")
                    Text(
                        "${me.username} · ${me.role ?: ""} · device ${me.deviceId?.take(12) ?: "not bound"} · Build ${BuildInfo.TAG}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(RmSpacing.ROW_GAP)
                    ) {
                        SecondaryButton(
                            onClick = { accountViewModel.refreshMe() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Refresh", maxLines = 1, softWrap = false)
                        }
                        SecondaryButton(
                            onClick = { accountViewModel.logout() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Log out", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
            item {
                AccountNavRow("Role & applications") {
                    viewModel.pushSubScreen(SubScreen.Account(AccountPage.ROLE))
                }
            }
        }
        // FIX4 (18 Nov): "Server & sync" is NOT a signed-in feature.
        // It used to live only in the signed-in branch above, so a
        // logged-out user could never reach ServerPage — the only
        // place a server URL can be saved — while login itself needs
        // that URL. Dead loop, and the exact banner in the 18 Nov
        // screenshot. The row is now unconditional: same destination
        // (AccountPage.SERVER), same save flow, signed in or not.
        // Role & applications stays signed-in-only, as before.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AccountNavRow("Server & sync") {
                    viewModel.pushSubScreen(SubScreen.Account(AccountPage.SERVER))
                }
                Text(
                    if (isDefaultServer) {
                        "Default: $serverUrl — tap to test another server"
                    } else {
                        "Custom: $serverUrl"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(syncStatus, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            // The green never-gated info card (preview, landing bottom).
            Box(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(RescueColors.MintTint)
                    .border(1.dp, Color(0xFFBDEED6), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 11.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StrokeGlyph(
                        modifier = Modifier.size(18.dp),
                        color = RescueColors.SafeGreen,
                        strokeWidth = 2.1f
                    ) {
                        drawPath(
                            shieldCheckPath(), RescueColors.SafeGreen,
                            style = Stroke(
                                width = 2.1f,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                join = androidx.compose.ui.graphics.StrokeJoin.Round
                            )
                        )
                    }
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) {
                                append("SOS is never gated.")
                            }
                            append(
                                " Home is always one tap away on the bar below — no login, no network."
                            )
                        },
                        style = TextStyle(
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = Color(0xFF0B4A35)
                        )
                    )
                }
            }
        }
    }
}

/** One of the hero's two white swipe boxes; tappable as well as swipeable. */
@Composable
private fun SwipeBox(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.82f))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                style = TextStyle(
                    fontFamily = ManropeFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.5.sp,
                    color = Color(0xFF173D2C)
                )
            )
            Text(
                body,
                style = TextStyle(
                    fontFamily = ManropeFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = Color(0xFF173D2C)
                )
            )
        }
    }
}

/**
 * The countdown as the preview's dark in-page card: a 34 dp red
 * circle with the Archivo numeral (3 → 2 → 1, from [SosCountdown]),
 * the cancel text beside it. Tapping the card cancels; the firing
 * itself still happens in AppViewModel's countdown job, which then
 * lands the app on Home.
 */
@Composable
private fun CountdownCard(viewModel: AppViewModel, deadline: Long) {
    var nowMs by remember(deadline) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(deadline) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(100)
        }
    }
    val elapsedMs = SosCountdown.DURATION_MS - (deadline - nowMs)
    val numeral = SosCountdown.secondsRemaining(elapsedMs) ?: 1
    Box(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(RescueColors.Ink)
            .clickable { viewModel.cancelSosCountdown() }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier.size(34.dp).clip(CircleShape)
                    .background(RescueColors.RescueRed),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    numeral.toString(),
                    style = TextStyle(
                        fontFamily = ArchivoFamily,
                        fontWeight = FontWeight.Black,
                        fontSize = 17.sp,
                        color = Color.White
                    )
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    "Swipe-right SOS countdown — tap anywhere to cancel.",
                    style = TextStyle(
                        fontFamily = ManropeFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp,
                        color = Color.White
                    )
                )
                Text(
                    "On zero, your SOS fires and you land on Home.",
                    style = TextStyle(
                        fontFamily = ManropeFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = Color(0xFFB9BEC7)
                    )
                )
            }
        }
    }
}

/** A white navigation row of the landing (Role & applications / Server & sync). */
@Composable
private fun AccountNavRow(title: String, onClick: () -> Unit) {
    RmCard(onClick = onClick, padding = 14.dp, spacing = 0.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = Color(0xFF9AA0A8)
            )
        }
    }
}

@Composable
private fun LoginPage(
    accountViewModel: AccountViewModel,
    onOpenSignUp: () -> Unit
) {
    val busy by accountViewModel.busy.collectAsState()
    var loginUsername by remember { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(RmSpacing.SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(RmSpacing.SECTION_GAP)
    ) {
        item { AccountStatusStrip(accountViewModel) }
        item {
            RmCard {
                RmTextField(
                    value = loginUsername,
                    onValueChange = { loginUsername = it },
                    label = "Username",
                    placeholder = "Username",
                    singleLine = true
                )
                RmTextField(
                    value = loginPassword,
                    onValueChange = { loginPassword = it },
                    label = "Password",
                    placeholder = "Password",
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                PrimaryButton(
                    onClick = { accountViewModel.login(loginUsername, loginPassword) },
                    enabled = !busy && loginUsername.isNotBlank() && loginPassword.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Log in", maxLines = 1, softWrap = false)
                }
                TextButton(onClick = onOpenSignUp) { Text("No account yet? Sign up") }
                // FIX5: the Render default is always in use unless a
                // custom URL was saved, so this page never needs a
                // "set server URL first" escape hatch any more (the
                // FIX4 one was removed with it).
            }
        }
    }
}

@Composable
private fun SignUpPage(accountViewModel: AccountViewModel, onOpenLogin: () -> Unit) {
    val busy by accountViewModel.busy.collectAsState()
    var signupUsername by remember { mutableStateOf("") }
    var signupEmail by remember { mutableStateOf("") }
    var signupPassword by remember { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(RmSpacing.SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(RmSpacing.SECTION_GAP)
    ) {
        item { AccountStatusStrip(accountViewModel) }
        item {
            RmCard {
                Text(
                    "New accounts are always CITIZEN. This phone's device ID is " +
                        "sent automatically — there is nothing else to fill in.",
                    style = MaterialTheme.typography.bodyMedium
                )
                RmTextField(
                    value = signupUsername,
                    onValueChange = { signupUsername = it },
                    label = "Username",
                    placeholder = "Username",
                    singleLine = true
                )
                RmTextField(
                    value = signupEmail,
                    onValueChange = { signupEmail = it },
                    label = "Email",
                    placeholder = "Email",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )
                RmTextField(
                    value = signupPassword,
                    onValueChange = { signupPassword = it },
                    label = "Password",
                    placeholder = "Password",
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                PrimaryButton(
                    onClick = {
                        accountViewModel.signup(signupUsername, signupEmail, signupPassword)
                    },
                    enabled = !busy && signupUsername.isNotBlank() &&
                        signupEmail.isNotBlank() && signupPassword.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sign up", maxLines = 1, softWrap = false)
                }
                TextButton(onClick = onOpenLogin) { Text("Already have an account? Log in") }
            }
        }
    }
}

@Composable
private fun VerifyPage(accountViewModel: AccountViewModel) {
    val busy by accountViewModel.busy.collectAsState()
    val pendingUsername by accountViewModel.pendingVerificationUsername.collectAsState()
    var verifyCode by remember { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(RmSpacing.SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(RmSpacing.SECTION_GAP)
    ) {
        item { AccountStatusStrip(accountViewModel) }
        item {
            RmCard {
                Text(
                    if (pendingUsername != null) {
                        "Enter the 6-digit code sent for $pendingUsername."
                    } else {
                        "Sign up or try logging in first — then enter the code here."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                RmTextField(
                    value = verifyCode,
                    onValueChange = { verifyCode = it },
                    label = "Verification code",
                    placeholder = "6-digit code",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(RmSpacing.ROW_GAP)
                ) {
                    PrimaryButton(
                        onClick = { accountViewModel.verifyEmail(verifyCode) },
                        enabled = !busy && verifyCode.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Verify", maxLines = 1, softWrap = false)
                    }
                    SecondaryButton(
                        onClick = { accountViewModel.resendVerification() },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Resend code", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun RolePage(accountViewModel: AccountViewModel) {
    val session by accountViewModel.session.collectAsState()
    val applications by accountViewModel.myApplications.collectAsState()
    val busy by accountViewModel.busy.collectAsState()
    var applyRole by remember { mutableStateOf("VOLUNTEER") }
    var applyOrg by remember { mutableStateOf("") }
    var applyReason by remember { mutableStateOf("") }
    var redeemField by remember { mutableStateOf("") }
    val me = session

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(RmSpacing.SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(RmSpacing.SECTION_GAP)
    ) {
        item { AccountStatusStrip(accountViewModel) }
        if (me == null) {
            item {
                RmCard {
                    Text(
                        "Sign in first — role applications and access codes belong to your account.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            item {
                RmCard {
                    Text("Apply for a role", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "An admin reviews the application. On approval you get a one-time access code by email.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    listOf("VOLUNTEER", "COORDINATOR").forEach { option ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            RadioButton(selected = applyRole == option, onClick = { applyRole = option })
                            Text(
                                option.lowercase().replaceFirstChar { it.uppercase() },
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                    }
                    RmTextField(
                        value = applyOrg,
                        onValueChange = { applyOrg = it },
                        label = "Organization (optional)",
                        singleLine = true
                    )
                    RmTextField(
                        value = applyReason,
                        onValueChange = { applyReason = it },
                        label = "Reason (optional)"
                    )
                    PrimaryButton(
                        onClick = { accountViewModel.applyRole(applyRole, applyOrg, applyReason) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Apply", maxLines = 1, softWrap = false)
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RmSpacing.ROW_GAP)
                ) {
                    Text(
                        "My applications (${applications.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    SecondaryButton(
                        onClick = { accountViewModel.loadMyApplications() },
                        enabled = !busy
                    ) {
                        Text("Reload", maxLines = 1, softWrap = false)
                    }
                }
                if (applications.isEmpty()) {
                    Text(
                        "No applications yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = RescueColors.Muted
                    )
                }
            }
            items(applications) { application ->
                RmCard(padding = 14.dp, spacing = 4.dp) {
                    Text(
                        "${application.requestedRole} · ${application.status}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    application.organization?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    // What this status actually means — APPROVED in
                    // particular is granted-but-not-active until the
                    // access code is redeemed.
                    Text(
                        applicationStatusExplanation(application),
                        style = MaterialTheme.typography.bodySmall,
                        color = RescueColors.Muted
                    )
                }
            }
            // An APPROVED application whose role is not yet active gets
            // a visible pointer straight to the redeem field below it.
            val awaitingActivation = applications.firstOrNull {
                isApprovedAwaitingActivation(it, me.role)
            }
            if (awaitingActivation != null) {
                item {
                    Text(
                        "You have an approved ${awaitingActivation.requestedRole} application — " +
                            "redeem your code below to activate it.",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            item {
                RmCard {
                    Text("Access code", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Got a code from an admin (email or in person)? Redeem it here — your role updates immediately.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    RmTextField(
                        value = redeemField,
                        onValueChange = { redeemField = it },
                        label = "Access code — e.g. RM-XXXXX-XXXXX",
                        singleLine = true
                    )
                    PrimaryButton(
                        onClick = { accountViewModel.redeemCode(redeemField) },
                        enabled = !busy && redeemField.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Redeem code", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerPage(accountViewModel: AccountViewModel) {
    val serverUrl by accountViewModel.serverUrl.collectAsState()
    val isDefault by accountViewModel.isUsingDefaultServer.collectAsState()
    val busy by accountViewModel.busy.collectAsState()
    val syncStatus by accountViewModel.syncStatus.collectAsState()
    var urlField by remember(serverUrl) { mutableStateOf(serverUrl) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(RmSpacing.SCREEN_PADDING),
        verticalArrangement = Arrangement.spacedBy(RmSpacing.SECTION_GAP)
    ) {
        item { AccountStatusStrip(accountViewModel) }
        item {
            RmCard {
                // Server URL is always editable, signed in or not.
                // FIX5: a fresh install already points at the Render
                // default — this field is only for testing another
                // server (e.g. a LAN URL). Saving overrides the
                // default; "Use default server" goes back to it.
                Text("Server", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (isDefault) {
                        "Using the default server — nothing to type. " +
                            "Change it below only to test another server."
                    } else {
                        "Using a custom server URL (default overridden)."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                RmTextField(
                    value = urlField,
                    onValueChange = { urlField = it },
                    label = "Server URL",
                    placeholder = "https://…onrender.com or http://192.168.x.x:8081",
                    singleLine = true
                )
                PrimaryButton(
                    onClick = { accountViewModel.saveServerUrl(urlField) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save server URL", maxLines = 1, softWrap = false)
                }
                if (!isDefault) {
                    SecondaryButton(
                        onClick = { accountViewModel.useDefaultServer() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Use default server", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        item {
            RmCard {
                // Gateway sync (Prompt 9): uploads stored records to
                // the server and applies verified server acks. Works
                // signed in or not (a device token is the fallback);
                // with no server URL it just says so. SOS and the
                // mesh never wait for this.
                Text("Gateway sync", style = MaterialTheme.typography.titleMedium)
                Text(syncStatus, style = MaterialTheme.typography.bodyMedium)
                SecondaryButton(
                    onClick = { accountViewModel.syncNow() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sync now", maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
