package com.rescuemesh

/**
 * The visible build tag — this round's proof instrument.
 *
 * Three rounds of UI fixes were reported "not applied" from device
 * screenshots while the patched code was verifiably inside the
 * delivered zips; with no marker in the app itself there was no way
 * to tell WHICH build a screenshot came from. This tag is shown on
 * the Account landing next to the device id (both the signed-out and
 * signed-in cards — see Screens.kt). Any screenshot of that screen
 * now proves the running build: if the tag is absent or older, the
 * phone is running an older build and no visual verdict from it
 * counts. Bump the value on every delivered build.
 */
object BuildInfo {
    const val TAG = "15Nov-FIX3"
}
