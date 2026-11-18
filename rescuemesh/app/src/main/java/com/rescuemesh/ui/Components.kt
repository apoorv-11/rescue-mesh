package com.rescuemesh.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Shared building blocks — LAYOUT REBUILD ROUND (15 Nov).
 *
 * The user's verdict on the earlier rounds was correct: they
 * re-skinned the old layouts (fonts, colours, shadows) but never
 * rebuilt the screens' STRUCTURE. These components now encode the
 * preview's own structure vocabulary, taken element-for-element from
 * `RescueMesh_UI_Redesign_Preview.html` (see
 * `ui-replica-blueprint-2026-11-15.md`): the custom bottom bar with
 * stroke icons and a red active dot, `.field` text inputs, btn-red /
 * btn-ghost shapes (16 dp rounded rects, NOT pills), state chips with
 * a leading dot, dashed detail dividers, and the preview's spacing.
 * CSS px = dp.
 */
object RmSpacing {
    /** Horizontal padding of every screen's content column (preview body: 16). */
    val SCREEN_PADDING = 16.dp

    /** Vertical gap between the sections of a screen (preview body gap: ~11). */
    val SECTION_GAP = 12.dp

    /** Inner padding of a standard card (preview .card: 14). */
    val CARD_PADDING = 14.dp

    /** Gap between the rows inside a card / the buttons in a row. */
    val ROW_GAP = 12.dp
}

/** Card corner radius from the preview HTML (22 dp). */
val RmCardShape = RoundedCornerShape(22.dp)

/** The preview's field / ghost-button border colour (#E7E0D6). */
val RmBorder = Color(0xFFE7E0D6)

/** The preview's dashed divider colour (#EAE3D8). */
val RmDash = Color(0xFFEAE3D8)

/**
 * THE card surface of the preview: pure white, NO border, and a
 * single soft diffused shadow in the ink colour at ~5–6 % alpha
 * (the preview's `rgba(21,24,29,.05/.06)`).
 */
fun Modifier.rmSurface(shape: Shape = RmCardShape): Modifier = this
    .shadow(
        elevation = 2.dp,
        shape = shape,
        ambientColor = Color(0x0D15181D),
        spotColor = Color(0x0F15181D)
    )
    .clip(shape)
    .background(Color.White)

/**
 * THE card: the [rmSurface] look with the content in a column padded
 * [padding] with [spacing] between rows. Clickable cards keep their
 * ripple, clipped to the card shape.
 */
@Composable
fun RmCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = RmSpacing.CARD_PADDING,
    spacing: Dp = RmSpacing.ROW_GAP,
    shape: Shape = RmCardShape,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .rmSurface(shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable { onClick() }
                } else {
                    Modifier
                }
            )
    ) {
        Column(
            modifier = Modifier.padding(padding),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content
        )
    }
}

/**
 * The preview's `.kicker`: 10.5 sp Manrope ExtraBold, .12 em tracking,
 * uppercase, muted. Callers pass any case; it is uppercased here,
 * like CSS `text-transform: uppercase`.
 */
@Composable
fun Kicker(text: String, color: Color = RescueColors.Muted, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = TextStyle(
            fontFamily = ManropeFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 10.5.sp,
            letterSpacing = 0.12.em
        ),
        color = color,
        modifier = modifier
    )
}

/** Section label — the preview's kicker, kept under its old name for the existing call sites. */
@Composable
internal fun SectionLabel(text: String) = Kicker(text)

/**
 * A state chip in the preview's shape: pill, a 6 dp dot in the chip
 * colour before the label, 10.5 sp ExtraBold. Colours come from the
 * single source ([StateColors]); the mesh metric rows reuse the same
 * composable with OK / FAILED labels.
 */
@Composable
fun StatusChip(label: String, colors: StateChipColors, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = Color(colors.background)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(colors.foreground))
            )
            Text(
                label,
                color = Color(colors.foreground),
                style = TextStyle(
                    fontFamily = ManropeFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 10.5.sp,
                    letterSpacing = 0.03.em
                )
            )
        }
    }
}

/** A record-state chip, coloured from [StateColors]. */
@Composable
internal fun StateChip(localState: String, modifier: Modifier = Modifier) {
    StatusChip(localState, StateColors.forState(localState), modifier)
}

/** The 4 dp state-coloured left border of a record / message card. Use inside a Row of IntrinsicSize.Min height. */
@Composable
internal fun StateBorder(localState: String) {
    Box(
        Modifier
            .width(4.dp)
            .fillMaxHeight()
            .background(Color(StateColors.forState(localState).border))
    )
}

/**
 * The preview's `.role-chip`: mint pill, dark-green text, a green dot
 * before the label. [background] / [textColor] / [dotColor] vary for
 * the Account hero's login-status chip (white .75 bg, amber-brown
 * text when logged out).
 */
@Composable
internal fun RoleChip(
    role: String,
    background: Color = RescueColors.MintTint,
    textColor: Color = Color(0xFF0B6B4F),
    dotColor: Color = RescueColors.SafeGreen
) {
    Surface(shape = RoundedCornerShape(50), color = background) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                role,
                color = textColor,
                style = TextStyle(
                    fontFamily = ManropeFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 10.5.sp,
                    letterSpacing = 0.05.em
                )
            )
        }
    }
}

/**
 * One label + switch row, the same shape everywhere it appears
 * (Mesh TTL test mode, the forms' switches): the label takes the free
 * width, the state text sits in a FIXED 40 dp end-aligned slot, then
 * the switch — so every switch lands on the same right edge at any
 * display width. [dark] is the dark relay card's variant (white
 * label, green-track switch).
 */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    stateText: String? = null,
    dark: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RmSpacing.ROW_GAP)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (dark) Color.White else RescueColors.Ink,
            modifier = Modifier.weight(1f)
        )
        Text(
            stateText ?: "",
            style = MaterialTheme.typography.labelLarge,
            color = if (dark) Color(0xFFB9BEC7) else RescueColors.Ink,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = if (dark) DarkSwitchColors else SwitchDefaults.colors()
        )
    }
}

/** The preview's dark-card switch: green track + white thumb when on. */
val DarkSwitchColors: SwitchColors
    @Composable get() = SwitchDefaults.colors(
        checkedTrackColor = RescueColors.SafeGreen,
        checkedThumbColor = Color.White,
        checkedBorderColor = Color.Transparent,
        uncheckedTrackColor = Color(0xFF3A3F47),
        uncheckedThumbColor = Color(0xFFB9BEC7),
        uncheckedBorderColor = Color.Transparent
    )

/** Button corner radius from the preview: btn-red / btn-ghost are 16 dp rounded rects, NOT pills. */
val RmButtonShape = RoundedCornerShape(16.dp)

/** btn-red label: 14.5 sp Manrope ExtraBold. */
private val PrimaryLabelStyle = TextStyle(
    fontFamily = ManropeFamily,
    fontWeight = FontWeight.ExtraBold,
    fontSize = 14.5.sp,
    lineHeight = 19.sp
)

/** btn-ghost label: 13.5 sp Manrope ExtraBold. */
private val GhostLabelStyle = TextStyle(
    fontFamily = ManropeFamily,
    fontWeight = FontWeight.ExtraBold,
    fontSize = 13.5.sp,
    lineHeight = 18.sp
)

/**
 * The preview's `.btn-red`: Rescue Red, 16 dp corners, 46 dp tall.
 * Layout rule (applied at every call site): buttons in a row each get
 * `Modifier.weight(1f)` so they come out equal-width; a lone action
 * button is `Modifier.fillMaxWidth()`. Labels never wrap — call
 * sites pass single-line Texts.
 *
 * RESPONSIVE (15 Nov): the 46 dp is a MINIMUM height, not a fixed
 * one (heightIn, not height). These two buttons were the only
 * text-bearing hard heights in the app; at font scale 1.3+ on a
 * narrow phone a label that needs more room would be clipped by a
 * fixed height — a min-height lets the button grow instead. At the
 * standard scale nothing changes: the button is exactly 46 dp.
 */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 46.dp),
        shape = RmButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = RescueColors.RescueRed,
            contentColor = Color.White
        ),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        ProvideTextStyle(PrimaryLabelStyle) { content() }
    }
}

/**
 * The preview's `.btn-ghost`: white, 1.5 dp #E7E0D6 border, 16 dp
 * corners, ink text, 46 dp tall. Same layout rule as [PrimaryButton],
 * including the min-height rule (see there): `heightIn(min = 46.dp)`,
 * never a fixed height, so large font scales grow rather than clip.
 */
@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 46.dp),
        shape = RmButtonShape,
        border = BorderStroke(1.5.dp, RmBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.White,
            contentColor = RescueColors.Ink
        ),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        ProvideTextStyle(GhostLabelStyle) { content() }
    }
}

/**
 * The preview's `.fchip` (Records filters + the forms' option chips):
 * white pill, 1.5 dp #E7E0D6 border, 11.5 sp ExtraBold; selected =
 * Rescue Red bg + white text (NOT the mint of the earlier rounds).
 */
@Composable
fun RmFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .clickable { onClick() },
        shape = RoundedCornerShape(50),
        color = if (selected) RescueColors.RescueRed else Color.White,
        border = BorderStroke(1.5.dp, if (selected) RescueColors.RescueRed else RmBorder)
    ) {
        Text(
            label,
            color = if (selected) Color.White else RescueColors.Ink,
            style = TextStyle(
                fontFamily = ManropeFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.5.sp
            ),
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * The preview's `.field` text input, used by onboarding, the account
 * pages and ALL form text inputs: white box, 1.5 dp #E7E0D6 border,
 * 16 dp corners; inside, the small-caps label sits ABOVE the value
 * (15 sp Manrope Bold, placeholder in #B6BCC4 SemiBold). All state /
 * keyboard / validation wiring of the field it replaces is preserved
 * — this is only its skin and structure.
 */
@Composable
fun RmTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    supportingText: String? = null,
    isError: Boolean = false
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .border(
                    1.5.dp,
                    if (isError) MaterialTheme.colorScheme.error else RmBorder,
                    RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                label.uppercase(),
                style = TextStyle(
                    fontFamily = ManropeFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 10.5.sp,
                    letterSpacing = 0.08.em
                ),
                color = RescueColors.Muted
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                singleLine = singleLine,
                keyboardOptions = keyboardOptions,
                visualTransformation = visualTransformation,
                textStyle = TextStyle(
                    fontFamily = ManropeFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = RescueColors.Ink
                ),
                cursorBrush = SolidColor(RescueColors.RescueRed),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                placeholder,
                                style = TextStyle(
                                    fontFamily = ManropeFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    color = Color(0xFFB6BCC4)
                                )
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
        if (supportingText != null) {
            Text(
                supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else RescueColors.Muted,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
    }
}

/** The preview's dashed detail divider (1.5 dp dashed #EAE3D8). */
@Composable
fun DashedDivider(modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(1.5.dp)
    ) {
        drawLine(
            color = RmDash,
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                floatArrayOf(6.dp.toPx(), 4.dp.toPx())
            )
        )
    }
}

/**
 * One cell of the preview's expanded-record detail grid: small-caps
 * label above a Bold value.
 */
@Composable
fun DetailCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            label.uppercase(),
            style = TextStyle(
                fontFamily = ManropeFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 10.sp,
                letterSpacing = 0.07.em
            ),
            color = RescueColors.Muted
        )
        Text(
            value,
            style = TextStyle(
                fontFamily = ManropeFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.5.sp,
                color = RescueColors.Ink
            )
        )
    }
}

// ------------------------------------------------------- stroke icons
// The preview's icons are 24×24 STROKE glyphs (2.1 width, round caps).
// They are drawn here with Canvas paths transcribed from the preview
// HTML's own SVG path data — guaranteed colours at any state, which
// the filled material glyphs never matched.

/**
 * Draws [draw] in a 24-unit space scaled to the canvas (stroke width
 * in 24-units).
 *
 * FORENSIC FIX ROUND (15 Nov): this helper is the single drawing
 * path behind EVERY custom glyph (bottom-bar icons, the shield in
 * the I Am Safe badge and the info cards, the record tiles, the
 * back chevron, the brand mark). It used to scale via
 * DrawTransform.scale(), whose pivot semantics caused the shipped
 * defect: with the default centre pivot every glyph was thrown
 * up-left, so unclipped icons merely looked off-centre while
 * clipped ones (the badge circle) showed only a sliver. The
 * pivot-at-origin rewrite still routed through the same
 * pivot-parameterised API.
 *
 * It now applies a raw [Matrix] scale through
 * DrawTransform.transform(). A matrix scale has NO pivot parameter
 * at all — it multiplies the coordinate axes about the origin,
 * full stop — so the entire class of pivot failure is removed, not
 * just re-parameterised: a point (x, y) in 24-space lands at
 * (x · width/24, y · width/24) on the canvas, exactly, on every
 * device. Glyphs therefore sit centred and whole wherever the
 * caller's canvas is centred.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.draw24(
    strokeWidth: Float = 2.1f,
    draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit
) {
    val scale = size.width / 24f
    val matrix = androidx.compose.ui.graphics.Matrix()
    matrix.scale(scale, scale, 1f)
    withTransform({ transform(matrix) }) {
        draw()
    }
}

private fun strokeStyle(width: Float) = Stroke(
    width = width,
    cap = StrokeCap.Round,
    join = StrokeJoin.Round
)

/** The shield-check glyph (preview: safe-card icon, green info card, safety tiles). */
fun shieldCheckPath(): Path = Path().apply {
    moveTo(12f, 3f)
    lineTo(19f, 6f)
    lineTo(19f, 11f)
    cubicTo(19f, 15.5f, 16f, 19.2f, 12f, 21f)
    cubicTo(8f, 19.2f, 5f, 15.5f, 5f, 11f)
    lineTo(5f, 6f)
    close()
    moveTo(9f, 12f)
    lineTo(11f, 14f)
    lineTo(15f, 10f)
}

/** The person glyph (preview: Missing record thumb). */
fun personPath(): Path = Path().apply {
    moveTo(5f, 20f)
    cubicTo(6.2f, 16.4f, 8.8f, 14.6f, 12f, 14.6f)
    cubicTo(15.2f, 14.6f, 17.8f, 16.4f, 19f, 20f)
}

/** A 24-space stroke icon drawn on a Canvas of the caller's size. */
@Composable
fun StrokeGlyph(
    modifier: Modifier = Modifier,
    color: Color,
    strokeWidth: Float = 2.1f,
    draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit
) {
    Canvas(modifier = modifier) {
        draw24(strokeWidth) { draw() }
    }
}

/**
 * The custom bottom-bar icon for [tab], drawn from the preview HTML's
 * exact SVG paths: Home (roof + walls), Records (rounded rect +
 * header lines), Mesh (three nodes + links), Account (head + shoulders).
 */
@Composable
fun RmNavIcon(tab: AppTab, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(21.dp)) {
        draw24 {
            val st = strokeStyle(2.1f)
            when (tab) {
                AppTab.HOME -> {
                    val path = Path().apply {
                        moveTo(3f, 10.5f)
                        lineTo(12f, 3f)
                        lineTo(21f, 10.5f)
                        moveTo(5f, 9.5f)
                        lineTo(5f, 21f)
                        lineTo(19f, 21f)
                        lineTo(19f, 9.5f)
                    }
                    drawPath(path, color, style = st)
                }
                AppTab.RECORDS -> {
                    val rect = Path().apply {
                        addRoundRect(
                            RoundRect(
                                rect = Rect(3f, 4f, 21f, 20f),
                                cornerRadius = CornerRadius(3f, 3f)
                            )
                        )
                        moveTo(3f, 9f)
                        lineTo(21f, 9f)
                        moveTo(8f, 4f)
                        lineTo(8f, 9f)
                    }
                    drawPath(rect, color, style = st)
                }
                AppTab.MESH -> {
                    val links = Path().apply {
                        moveTo(7f, 11f)
                        lineTo(17f, 7f)
                        moveTo(7f, 13f)
                        lineTo(17f, 17f)
                    }
                    drawPath(links, color, style = st)
                    drawCircle(color, radius = 2.2f, center = Offset(5f, 12f), style = st)
                    drawCircle(color, radius = 2.2f, center = Offset(19f, 6f), style = st)
                    drawCircle(color, radius = 2.2f, center = Offset(19f, 18f), style = st)
                }
                AppTab.ACCOUNT -> {
                    drawCircle(color, radius = 4f, center = Offset(12f, 8f), style = st)
                    val shoulders = Path().apply {
                        moveTo(4f, 21f)
                        cubicTo(5.5f, 17f, 8.5f, 15f, 12f, 15f)
                        cubicTo(15.5f, 15f, 18.5f, 17f, 20f, 21f)
                    }
                    drawPath(shoulders, color, style = st)
                }
            }
        }
    }
}

/** The preview's in-body back row for pushed sub-screens: chevron + Archivo title. */
@Composable
fun BackRow(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onBack() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            draw24 {
                val path = Path().apply {
                    moveTo(14.5f, 5f)
                    lineTo(8f, 12f)
                    lineTo(14.5f, 19f)
                }
                drawPath(path, RescueColors.Ink, style = strokeStyle(2.4f))
            }
        }
        Text(
            title,
            style = TextStyle(
                fontFamily = ArchivoFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 19.sp,
                color = RescueColors.Ink
            )
        )
    }
}

/** A screen's in-body header (preview: Archivo Black 22 title + 12 sp SemiBold muted subtitle). */
@Composable
fun ScreenHeader(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            title,
            style = TextStyle(
                fontFamily = ArchivoFamily,
                fontWeight = FontWeight.Black,
                fontSize = 22.sp,
                color = RescueColors.Ink
            )
        )
        Text(
            subtitle,
            style = TextStyle(
                fontFamily = ManropeFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                color = RescueColors.Muted
            )
        )
    }
}
