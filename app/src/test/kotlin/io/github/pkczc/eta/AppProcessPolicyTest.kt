package io.github.pkczc.eta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProcessPolicyTest {
    @Test
    fun `仅主进程初始化完整 Runtime 依赖`() {
        assertTrue(AppProcessPolicy.shouldInitializeFullRuntime("io.github.pkczc.eta", "io.github.pkczc.eta"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.pkczc.eta:voice", "io.github.pkczc.eta"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.pkczc.eta:voice_session", "io.github.pkczc.eta"))
    }
}
