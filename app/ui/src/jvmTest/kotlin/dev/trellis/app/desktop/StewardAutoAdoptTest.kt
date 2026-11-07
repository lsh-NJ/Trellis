package dev.trellis.app.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 自动采纳的准入判断：开关 + 前台 + 每日上限 + 类型白名单（G-C1 最小版）。 */
class StewardAutoAdoptTest {

    private fun proposal(vararg changes: StewardChange) = StewardProposal(
        id = "prop-1",
        title = "测试建议",
        detail = "细节",
        changes = changes.toList(),
    )

    @Test
    fun offWhenTheSwitchIsOff() {
        assertFalse(autoAdoptAllowed(StewardSettings(autoAdopt = false), appFocused = false, adoptedToday = 0))
        assertFalse(autoAdoptAllowed(StewardSettings(autoAdopt = false), appFocused = false, adoptedToday = 2))
    }

    @Test
    fun offWhileTheAppIsFocused() {
        val settings = StewardSettings(autoAdopt = true)
        assertFalse(autoAdoptAllowed(settings, appFocused = true, adoptedToday = 0))
    }

    @Test
    fun onOnlyWhenUnfocusedAndUnderTheDailyLimit() {
        val settings = StewardSettings(autoAdopt = true, autoAdoptDailyLimit = 3)
        assertTrue(autoAdoptAllowed(settings, appFocused = false, adoptedToday = 0))
        assertTrue(autoAdoptAllowed(settings, appFocused = false, adoptedToday = 2))
        assertFalse(autoAdoptAllowed(settings, appFocused = false, adoptedToday = 3))
        assertFalse(autoAdoptAllowed(settings, appFocused = false, adoptedToday = 4))
    }

    @Test
    fun onlyPureMoveProposalsAreAutoAdoptable() {
        val move = StewardChange.Move(title = "课时1", occurrenceId = "occ_1", day = "2026-11-07")
        val place = StewardChange.Place(title = "课时9", unitId = "unit_9", day = "2026-11-07")
        assertTrue(proposal(move, move).isAutoAdoptable())
        assertFalse(proposal(move, place).isAutoAdoptable())
        assertFalse(proposal(place).isAutoAdoptable())
        assertFalse(proposal().isAutoAdoptable())
    }
}
