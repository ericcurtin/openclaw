package ai.openclaw.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalModeNavigationTest {
  @Test
  fun onlyPhoneBackedSettingsRoutesWorkWithoutAGateway() {
    assertEquals(
      setOf(SettingsRoute.Home, SettingsRoute.Profile, SettingsRoute.Appearance, SettingsRoute.Gateway, SettingsRoute.About, SettingsRoute.Licenses),
      SettingsRoute.entries.filter { it.worksWithoutGateway() }.toSet(),
    )
  }

  @Test
  fun theSidebarKeepsOnlyChatThreadsAndSettingsInLocalMode() {
    assertEquals(
      setOf(SidebarDestination.Home, SidebarDestination.Threads, SidebarDestination.Settings),
      SidebarDestination.entries.filter { it.worksWithoutGateway() }.toSet(),
    )
  }

  @Test
  fun everyGatewayBackedSidebarPageIsHiddenInLocalMode() {
    val hidden = SidebarDestination.entries.filterNot { it.worksWithoutGateway() }
    assertTrue(hidden.containsAll(listOf(SidebarDestination.Work, SidebarDestination.Skills, SidebarDestination.Agents, SidebarDestination.Automations, SidebarDestination.Usage)))
    // A hidden page's settings route must also refuse to open directly.
    hidden.mapNotNull { it.settingsRoute }.forEach { route -> assertTrue("$route", !route.worksWithoutGateway()) }
  }
}
