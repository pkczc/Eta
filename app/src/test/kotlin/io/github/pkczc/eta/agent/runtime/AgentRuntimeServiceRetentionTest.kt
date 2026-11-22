package io.github.pkczc.eta.agent.runtime

import android.app.Application
import android.os.Message
import android.os.Process
import io.github.pkczc.eta.agent.model.AgentModelClient
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentRuntimeServiceRetentionTest {
    @Test
    fun vendorEntryMessagesRequireAnAllowedSenderUid() {
        val service = Robolectric.buildService(AgentRuntimeService::class.java).get()
        val packages = shadowOf(service.packageManager)
        packages.setPackagesForUid(20001, "com.heytap.speechassist")
        packages.setPackagesForUid(20002, "com.miui.voiceassist")
        packages.setPackagesForUid(20003, "untrusted.app")
        val allowed = AgentRuntimeService::class.java.getDeclaredMethod(
            "isMessageSenderAllowed", Message::class.java,
        ).apply { isAccessible = true }
        fun accepts(uid: Int): Boolean {
            val message = Message.obtain().apply { sendingUid = uid }
            return try {
                allowed.invoke(service, message) as Boolean
            } finally {
                message.recycle()
            }
        }

        assertTrue(accepts(Process.myUid()))
        assertTrue(accepts(20001))
        assertTrue(accepts(20002))
        assertFalse(accepts(20003))
        assertFalse(accepts(20004))
    }

    @Test
    fun dismissReleasesContinuationHistoryBeforeTheServiceIsDestroyed() {
        val controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = controller.get()
            val request = AgentRuntimeWire.RunRequest(
                runId = "completed-run",
                prompt = "请求",
                config = AgentModelClient.ModelConfig(
                    baseUrl = "https://example.invalid/v1",
                    apiKey = "test-key",
                    model = "test-model",
                    systemPrompt = "",
                ),
                images = emptyList(),
                history = listOf(AgentModelClient.ConversationMessage("user", "已完成的历史")),
            )
            val run = RuntimeRunRegistry.Run(request, AgentRuntimeSession(request.runId)).apply {
                completedContext = RuntimeRunRegistry.CompletedContext(request, AgentModelClient.ModelResponse.Text("完成"))
            }
            val retainedRun = AgentRuntimeService::class.java.getDeclaredField("displayedRun").apply {
                isAccessible = true
            }
            retainedRun.set(service, run)
            assertSame(run, retainedRun.get(service))

            AgentRuntimeService::class.java.getDeclaredMethod("dismissAndStop").apply {
                isAccessible = true
            }.invoke(service)

            assertNull(retainedRun.get(service))
            assertNull(run.completedContext)
        } finally {
            controller.destroy()
        }
    }
}
