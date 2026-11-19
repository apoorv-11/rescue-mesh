package com.rescuemesh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tab router reducer (UI redesign spec): the app opens on Account,
 * tab switches preserve per-tab stacks, push/pop work within the
 * selected tab only, and system Back at a tab root is NOT handled by
 * the router (null) so it can never swallow the press or hop tabs.
 */
class AppNavTest {

    @Test
    fun `the app opens on the Account tab at its root`() {
        val state = NavState()
        assertEquals(AppTab.ACCOUNT, state.tab)
        assertTrue(state.atTabRoot)
        assertNull(state.current)
    }

    @Test
    fun `selectTab switches and preserves every stack`() {
        var state = NavState()
        state = AppNav.push(state, SubScreen.Account(AccountPage.LOGIN))
        state = AppNav.selectTab(state, AppTab.RECORDS)
        assertEquals(AppTab.RECORDS, state.tab)
        assertTrue(state.atTabRoot)
        state = AppNav.push(state, SubScreen.Form(FormKind.MISSING))
        state = AppNav.selectTab(state, AppTab.ACCOUNT)
        assertEquals(SubScreen.Account(AccountPage.LOGIN), state.current)
        state = AppNav.selectTab(state, AppTab.RECORDS)
        assertEquals(SubScreen.Form(FormKind.MISSING), state.current)
    }

    @Test
    fun `push and pop work on the selected tab only`() {
        var state = NavState(tab = AppTab.HOME)
        state = AppNav.push(state, SubScreen.Form(FormKind.SOS))
        state = AppNav.push(state, SubScreen.Form(FormKind.FOUND))
        assertEquals(SubScreen.Form(FormKind.FOUND), state.current)
        state = AppNav.pop(state)
        assertEquals(SubScreen.Form(FormKind.SOS), state.current)
        state = AppNav.pop(state)
        assertTrue(state.atTabRoot)
        // Pop at the root is a no-op, never a crash or a tab hop.
        val same = AppNav.pop(state)
        assertEquals(state, same)
        assertEquals(AppTab.HOME, same.tab)
    }

    @Test
    fun `back pops a sub-screen and returns null at a tab root`() {
        var state = NavState()
        assertNull(AppNav.back(state))
        state = AppNav.push(state, SubScreen.Account(AccountPage.SERVER))
        val popped = AppNav.back(state)
        assertEquals(AppTab.ACCOUNT, popped!!.tab)
        assertTrue(popped.atTabRoot)
        assertNull(popped.current)
        // Still null once back at the root — Back stays unhandled there.
        assertNull(AppNav.back(popped!!))
    }

    @Test
    fun `back never switches tabs`() {
        var state = NavState(tab = AppTab.MESH)
        assertNull(AppNav.back(state))
        state = AppNav.push(state, SubScreen.Account(AccountPage.LOGIN))
        val popped = AppNav.back(state)!!
        assertEquals(AppTab.MESH, popped.tab)
        assertTrue(popped.atTabRoot)
    }

    @Test
    fun `Prepare hub pushes over Home without touching the bottom bar`() {
        var state = NavState(tab = AppTab.HOME)
        state = AppNav.push(state, SubScreen.PrepareList)
        assertEquals(SubScreen.PrepareList, state.current)
        assertEquals(AppTab.HOME, state.tab)
        state = AppNav.push(state, SubScreen.PrepareDetail("first-aid"))
        assertEquals(SubScreen.PrepareDetail("first-aid"), state.current)
        state = AppNav.pop(state)
        assertEquals(SubScreen.PrepareList, state.current)
        state = AppNav.pop(state)
        assertTrue(state.atTabRoot)
        assertEquals(AppTab.HOME, state.tab)
        // And the tab set itself never gained a Prepare tab.
        assertEquals(listOf(AppTab.HOME, AppTab.RECORDS, AppTab.MESH, AppTab.ACCOUNT), AppTab.values().toList())
    }

    @Test
    fun `popToRoot clears only the selected tab`() {
        var state = NavState()
        state = AppNav.push(state, SubScreen.Account(AccountPage.LOGIN))
        state = AppNav.push(state, SubScreen.Account(AccountPage.VERIFY))
        state = AppNav.selectTab(state, AppTab.RECORDS)
        state = AppNav.push(state, SubScreen.Form(FormKind.SOS))
        state = AppNav.popToRoot(state)
        assertTrue(state.atTabRoot)
        state = AppNav.selectTab(state, AppTab.ACCOUNT)
        assertEquals(SubScreen.Account(AccountPage.VERIFY), state.current)
    }

    @Test
    fun `swipe classification honours the threshold`() {
        val threshold = 240f
        assertEquals(SwipeDirection.LEFT, classifySwipe(-300f, threshold))
        assertEquals(SwipeDirection.LEFT, classifySwipe(-240f, threshold))
        assertEquals(SwipeDirection.RIGHT, classifySwipe(300f, threshold))
        assertEquals(SwipeDirection.RIGHT, classifySwipe(240f, threshold))
        assertEquals(SwipeDirection.NONE, classifySwipe(0f, threshold))
        assertEquals(SwipeDirection.NONE, classifySwipe(-239f, threshold))
        assertEquals(SwipeDirection.NONE, classifySwipe(120f, threshold))
    }
}
