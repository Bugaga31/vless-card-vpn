package com.vlesscardvpn
import com.vlesscardvpn.domain.AppAppearance
import org.junit.Assert.*
import org.junit.Test
class AppAppearanceTest {
    @Test fun systemModeFollowsDevice() { assertTrue(AppAppearance.isDark("system", true)); assertFalse(AppAppearance.isDark("system", false)) }
    @Test fun explicitChoiceOverridesDevice() { assertTrue(AppAppearance.isDark("dark", false)); assertFalse(AppAppearance.isDark("light", true)) }
    @Test fun invalidSavedChoiceFallsBackToSystem() { assertEquals("system", AppAppearance.normalize("broken")); assertTrue(AppAppearance.isDark("broken", true)) }
}
