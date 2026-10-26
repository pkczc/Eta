package io.github.pkczc.eta.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.pkczc.eta.agent.browser.AgentBrowserSession

internal val LocalAgentBrowserSession = staticCompositionLocalOf<AgentBrowserSession?> { null }
