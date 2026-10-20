package dev.trellis.dsl

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** 只验一件事：entry JSON 里的 rules 能不能被解析出来。 */
class RuleSchemaTest {

    @Test
    fun theEntryJsonCanCarryRules() {
        val json = Json { ignoreUnknownKeys = false }
        val entry = json.decodeFromString(
            DslEntryJson.serializer(),
            """{"slots":[],"rules":[{"on":"task.completed","do":[{"action":"addTask","args":{"title":"复习：{{ event.title }}"}}]}]}""",
        )

        assertEquals(1, entry.rules.size, "rules 没被解析出来")
        assertEquals("task.completed", entry.rules[0].on)
        assertEquals(1, entry.rules[0].actions.size)
        assertEquals("addTask", entry.rules[0].actions[0].action)
        assertEquals("复习：{{ event.title }}", entry.rules[0].actions[0].args["title"])
    }
}
