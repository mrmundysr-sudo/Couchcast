package com.mundymorning.couchcast

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Cast device chooser is an AppCompatDialog; on a non-AppCompat activity theme it throws
 * "You need to use a Theme.AppCompat theme" the moment the Cast icon is tapped (seen on a real phone, build 15).
 * A JVM test cannot open the dialog, so this guards the cause.
 */
class ThemeGuardTest {
    @Test fun appThemeIsAnAppCompatTheme() {
        val xml = listOf("src/main/res/values/themes.xml", "app/src/main/res/values/themes.xml").map(::File).first { it.exists() }.readText()
        val parent = Regex("""<style\s+name="Theme\.Couchcast"\s+parent="([^"]+)"""").find(xml)?.groupValues?.get(1)
        assertTrue("Theme.Couchcast parent was $parent", parent != null && parent.startsWith("Theme.AppCompat"))
    }

    @Test fun manifestUsesThatTheme() {
        val xml = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }.readText()
        assertTrue(xml.contains("@style/Theme.Couchcast"))
    }
}
