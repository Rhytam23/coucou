package com.coucou.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** The debug receiver is compiled only into debug builds, which no other unit test reads: keep it in step with AppModel. */
class DebugReceiverTest {
    @Test fun everyModelMemberTheReceiverUsesStillExists() {
        val receiver = File("src/debug/kotlin/com/coucou/android/app/DebugPillReceiver.kt").readText()
        val model = File("src/main/kotlin/com/coucou/android/app/AppModel.kt").readText()
        val used = Regex("""\bmodel\.([A-Za-z_][A-Za-z0-9_]*)""").findAll(receiver).map { it.groupValues[1] }.toSet()
        assertTrue("the receiver should use the model", used.isNotEmpty())
        for (name in used) {
            val declared = Regex("""\b(fun|val|var)\s+(?:<[^>]+>\s*)?(?:[A-Za-z0-9_.<>?]+\.)?$name\b""").containsMatchIn(model)
            assertTrue("DebugPillReceiver uses model.$name but AppModel does not declare it", declared)
        }
    }
}
