package com.slukhayka.audiobooks.testing

import androidx.activity.ComponentActivity

/**
 * #766 A2 — a CONTENT-FREE host activity for instrumented tests that render
 * their own composition.
 *
 * `MainActivity` calls `setContent` in `onCreate`, and
 * `createAndroidComposeRule<MainActivity>()` installs its own content too — so
 * a test that supplies its own tree hits "MainActivity has already set
 * content" (or, earlier, the detached-hierarchy "No compose hierarchies
 * found"). This host declares nothing, so the rule and the test can set the
 * content exactly once.
 */
class TestHostActivity : ComponentActivity()
