package io.github.pkczc.eta.agent.model

import io.github.pkczc.eta.agent.tool.AgentToolRequirements
import io.github.pkczc.eta.agent.tool.RootRequirement
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentWebToolCatalogTest {
    @Test
    fun publicWebToolsNeedNeitherRootNorAccessibility() {
        listOf("web_search", "fetch_url").forEach { name ->
            val requirement = requireNotNull(AgentToolRequirements.find(name))
            assertEquals(RootRequirement.NONE, requirement.rootRequirement)
            assertEquals(false, requirement.accessibility)
        }
    }

}
