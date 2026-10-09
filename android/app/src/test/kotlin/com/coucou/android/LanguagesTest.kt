package com.coucou.android

import com.coucou.android.core.Languages
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagesTest {
    private val res = File("src/main/res")

    @Test fun theLanguageListIsTheDesktopOne() {
        val desktop = Regex(""""languages":\s*\[([^\]]*)\]""").find(ReferenceFiles.read("windows/src/i18n/strings.json"))!!
            .groupValues[1].split(",").map { it.trim().trim('"') }
        assertEquals(desktop, Languages.all.map { it.first })
    }

    @Test fun androidOffersExactlyTheseLanguagesInItsPerAppSettings() {
        val xml = File(res, "xml/locales_config.xml").readText()
        val tags = Regex("""android:name="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(Languages.all.map { it.first }, tags)
    }

    @Test fun everyLanguageHasItsStringsFolder() {
        val folder = mapOf("zh-Hans" to "values-b+zh+Hans", "pt-BR" to "values-pt-rBR", "id" to "values-in")
        for ((tag, _) in Languages.all) {
            if (tag == "en") continue
            assertTrue(tag, File(res, folder[tag] ?: "values-$tag").isDirectory)
        }
    }

    @Test fun theManifestPointsAtTheLocaleList() {
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("android:localeConfig=\"@xml/locales_config\""))
    }
}
