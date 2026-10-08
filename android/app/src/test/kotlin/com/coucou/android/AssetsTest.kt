package com.coucou.android

import com.coucou.android.mochi.MochiConst
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetsTest {
    private val raw = File("src/main/res/raw")
    private val res = File("src/main/res")

    @Test fun everySoundTheDesktopShipsIsBundled() {
        val desktop = ReferenceFiles.file("NotchBuddy/Resources/sounds").listFiles { f -> f.extension == "wav" }!!
        assertEquals(desktop.map { it.name }.sorted(), raw.listFiles()!!.map { it.name }.sorted())
        desktop.forEach { assertEquals(it.name, it.length(), File(raw, it.name).length()) }
    }

    @Test fun stateSoundsExistInRaw() {
        MochiConst.STATE_SOUND.values.forEach { assertTrue("$it.wav", File(raw, "$it.wav").exists()) }
    }

    @Test fun rawNamesAreValidAndroidResourceNames() {
        raw.listFiles()!!.forEach { assertTrue(it.name, Regex("""[a-z][a-z0-9_]*\.wav""").matches(it.name)) }
    }

    @Test fun translationsOnlyUseKnownKeys() {
        fun keys(f: File) = Regex("name=\"([a-z_]+)\"").findAll(f.readText()).map { it.groupValues[1] }.toSet()
        val base = keys(File(res, "values/strings.xml"))
        val dirs = res.listFiles { f -> f.name.startsWith("values-") }!!
        assertEquals("10 languages besides English minus none missing", 9, dirs.size)
        dirs.forEach { assertTrue(it.name, base.containsAll(keys(File(it, "strings.xml")))) }
    }

    @Test fun soundTableCoversEveryBundledSound() {
        val src = File("src/main/kotlin/com/coucou/android/sound/SoundPlayer.kt").readText()
        val mapped = Regex("\"(\\w+)\" to R\\.raw\\.(\\w+)").findAll(src).map { assertEquals(it.groupValues[1], it.groupValues[2]); it.groupValues[1] }.toSet()
        assertEquals(raw.listFiles()!!.map { it.nameWithoutExtension }.toSet(), mapped)
    }

    @Test fun indonesianUsesTheLegacyFolderCode() {
        assertTrue(File(res, "values-in/strings.xml").exists())
        assertTrue(!File(res, "values-id").exists())
    }

    @Test fun notificationIconIsMonochromeVector() {
        assertTrue(File(res, "drawable/ic_stat_mochi.xml").readText().contains("#FFFFFFFF"))
    }

    @Test fun launcherIconIsPresent() {
        assertTrue(File(res, "mipmap-xxxhdpi/ic_launcher.png").length() > 1000)
    }
}
