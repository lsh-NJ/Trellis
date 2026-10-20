package dev.trellis.dsl

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** 规则的条件能不能从 JSON 里读出来。 */
class RuleConditionSchemaTest {

    @Test
    fun theEntryJsonCanCarryAWhenCondition() {
        val json = Json { ignoreUnknownKeys = false }
        val entry = json.decodeFromString(
            DslEntryJson.serializer(),
            """{"slots":[],"rules":[{"on":"task.completed","when":"!startsWith(event.title, '复习：')","do":[{"action":"addTask","args":{"title":"复习：{{ event.title }}"}}]}]}""",
        )

        assertEquals("!startsWith(event.title, '复习：')", entry.rules[0].condition)
    }
}
