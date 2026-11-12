package io.github.pkczc.eta.hook.breeno

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.pkczc.eta.EtaApp
import io.github.pkczc.eta.R
import io.github.pkczc.eta.data.model.AppearanceSettings
import io.github.pkczc.eta.data.model.AppearanceUiStyle
import io.github.pkczc.eta.ui.SettingsScreen
import io.github.pkczc.eta.ui.app.AgentAppTheme
import io.github.pkczc.eta.ui.app.EnhancementSettingsHistory
import java.time.Duration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(application = EtaApp::class, sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class BreenoSettingsScreenTest {
    @Test
    fun exclusiveTakeoverIsVisibleInBothSettingsStyles() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val looper = shadowOf(Looper.getMainLooper())
        // Robolectric 未填充 Manifest 的 localeConfig，补齐设置页所需的应用元数据。
        val applicationInfo = activity.applicationContext.applicationInfo
        val setLocaleConfig = applicationInfo.javaClass.getMethod("setLocaleConfigRes", Int::class.javaPrimitiveType!!)
        val previousLocaleConfig = applicationInfo.javaClass.getMethod("getLocaleConfigRes").invoke(applicationInfo)
        setLocaleConfig.invoke(applicationInfo, R.xml.locales_config)
        EnhancementSettingsHistory(activity).captureConnected(
            activity.getSharedPreferences("breeno_settings_test_hook_preferences", Context.MODE_PRIVATE),
        )
        var uiStyle by mutableStateOf(AppearanceUiStyle.COUI)

        try {
            activity.setContent {
                AgentAppTheme(
                    appearance = AppearanceSettings(uiStyle = uiStyle),
                    applyInterfaceScale = true,
                ) {
                    SettingsScreen(context = activity, onNavigate = {}, onBack = {})
                }
            }
            for (style in AppearanceUiStyle.entries) {
                uiStyle = style
                looper.idle()
                measureAndLayout(activity)
                looper.idleFor(Duration.ofMillis(32))
                measureAndLayout(activity)
                looper.idle()
                val title = activity.getString(R.string.ui_breeno_exclusive_takeover_title)
                assertTrue(
                    "Expected the exclusive Breeno takeover switch to be visible in $style",
                    semanticsNodes(activity.window.decorView).any { node ->
                        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == title } == true &&
                            node.boundsInRoot.height > 0f && node.boundsInRoot.top >= 0f &&
                            node.boundsInRoot.bottom <= activity.window.decorView.height
                    },
                )
            }
        } finally {
            setLocaleConfig.invoke(applicationInfo, previousLocaleConfig)
            activity.getSharedPreferences("eta_enhancement_ui_history", Context.MODE_PRIVATE)
                .edit().clear().commit()
            controller.pause().stop().destroy()
        }
    }

    private fun measureAndLayout(activity: ComponentActivity) {
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1080, 2400)
    }

    private fun semanticsNodes(root: View): List<SemanticsNode> {
        val result = mutableListOf<SemanticsNode>()
        fun visit(view: View) {
            if (view is AbstractComposeView) {
                view.getChildAt(0)?.let { child ->
                    val owner = child.javaClass.getMethod("getSemanticsOwner").invoke(child)
                    val node = owner.javaClass.getMethod("getUnmergedRootSemanticsNode")
                        .invoke(owner) as SemanticsNode
                    fun collect(current: SemanticsNode) {
                        result.add(current)
                        current.children.forEach(::collect)
                    }
                    collect(node)
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
        return result
    }
}
