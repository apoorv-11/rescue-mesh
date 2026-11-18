package com.rescuemesh.ui

/**
 * The hand-rolled tab router (UI redesign spec, 14 Nov) — no navigation
 * library, just a pure state + reducer the Compose root renders.
 *
 * Shape: four tabs, each with its own push stack of sub-screens (forms
 * push inside Records, login pages inside Account). The bottom bar is
 * always visible at tab roots; the top app bar shows a back arrow
 * whenever the current tab has a pushed sub-screen, and system Back
 * pops it too. Switching tabs preserves every tab's stack.
 *
 * The app opens on the ACCOUNT tab ([NavState]'s default): the entry /
 * landing screen lives there, and the bottom bar keeps Home — and with
 * it SOS — one tap away at all times. Nothing about navigation ever
 * gates a feature: it only moves the user's eyes.
 *
 * Pure Kotlin on purpose (no Android, no Compose) so the whole reducer
 * is JVM-unit-tested (AppNavTest).
 */

/** The four bottom-bar tabs, in bar order. */
enum class AppTab(val title: String) {
    HOME("RescueMesh"),
    RECORDS("Records"),
    MESH("Mesh"),
    ACCOUNT("Account")
}

/** The five record-creation forms, pushed inside the Records tab. */
enum class FormKind(val title: String) {
    SOS("SOS form"),
    MISSING("Missing person"),
    FOUND("Found update"),
    ANNOUNCEMENT("Announcement"),
    RESOURCE("Resource update")
}

/** The Account tab's sub-screens, pushed over the landing. */
enum class AccountPage(val title: String) {
    LOGIN("Log in"),
    SIGN_UP("Sign up"),
    VERIFY("Verify email"),
    ROLE("Role & applications"),
    SERVER("Server & sync")
}

/** A screen pushed within a tab (the tab roots themselves are implicit). */
sealed interface SubScreen {
    data class Form(val kind: FormKind) : SubScreen
    data class Account(val page: AccountPage) : SubScreen
}

/**
 * The full navigation state: the selected tab plus one push stack per
 * tab. [stack] / [current] are always about the SELECTED tab.
 */
data class NavState(
    val tab: AppTab = AppTab.ACCOUNT,
    val stacks: Map<AppTab, List<SubScreen>> = emptyMap()
) {
    /** The selected tab's push stack (empty at its root). */
    val stack: List<SubScreen> get() = stacks[tab].orEmpty()

    /** The topmost pushed sub-screen of the selected tab, or null at its root. */
    val current: SubScreen? get() = stack.lastOrNull()

    /** True when the selected tab is showing its root screen. */
    val atTabRoot: Boolean get() = stack.isEmpty()
}

/** Pure reducer over [NavState]; every function returns a new state. */
object AppNav {

    /** Bottom-bar tap: switch tabs, keeping every tab's stack as it was. */
    fun selectTab(state: NavState, tab: AppTab): NavState = state.copy(tab = tab)

    /** Push a sub-screen onto the selected tab's stack. */
    fun push(state: NavState, screen: SubScreen): NavState =
        state.copy(stacks = state.stacks + (state.tab to (state.stack + screen)))

    /** Pop the selected tab's top sub-screen; a no-op at its root. */
    fun pop(state: NavState): NavState =
        if (state.stack.isEmpty()) state
        else state.copy(stacks = state.stacks + (state.tab to state.stack.dropLast(1)))

    /** Pop everything above the selected tab's root. */
    fun popToRoot(state: NavState): NavState =
        if (state.stack.isEmpty()) state
        else state.copy(stacks = state.stacks + (state.tab to emptyList()))

    /**
     * System Back: the new state after popping, or null when the
     * selected tab is already at its root — Back is then NOT handled by
     * the router (it never switches tabs or swallows the press; the
     * activity keeps its default behaviour).
     */
    fun back(state: NavState): NavState? =
        if (state.atTabRoot) null else pop(state)
}

/** Direction of a completed horizontal swipe, from [classifySwipe]. */
enum class SwipeDirection { LEFT, RIGHT, NONE }

/**
 * Classifies a finished horizontal drag on the Account landing by its
 * total travel: at or beyond [thresholdPx] in either direction it is a
 * swipe (left → the login pages, right → the SOS countdown); anything
 * shorter is not a navigation gesture at all.
 */
fun classifySwipe(totalDragPx: Float, thresholdPx: Float): SwipeDirection = when {
    totalDragPx <= -thresholdPx -> SwipeDirection.LEFT
    totalDragPx >= thresholdPx -> SwipeDirection.RIGHT
    else -> SwipeDirection.NONE
}
