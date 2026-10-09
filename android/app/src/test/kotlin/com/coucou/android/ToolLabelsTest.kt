package com.coucou.android

import com.coucou.android.core.ToolLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ToolLabelsTest {
    @Test fun knownToolsBecomePlainSentences() {
        assertEquals("Asking a question", ToolLabels.label("ask_question"))
        assertEquals("Asking a question", ToolLabels.label("AskUserQuestion"))
        assertEquals("Running a command", ToolLabels.label("Bash"))
        assertEquals("Reading files", ToolLabels.label("Read"))
        assertEquals("Editing files", ToolLabels.label("Edit"))
        assertEquals("Editing files", ToolLabels.label("MultiEdit"))
        assertEquals("Editing files", ToolLabels.label("write_file"))
        assertEquals("Searching files", ToolLabels.label("Grep"))
        assertEquals("Searching the web", ToolLabels.label("WebSearch"))
    }

    @Test fun caseAndSeparatorsDoNotMatter() {
        assertEquals("Running a command", ToolLabels.label("  bash  "))
        assertEquals("Running a command", ToolLabels.label("BASH"))
        assertEquals("Running a command", ToolLabels.label("run_shell_command"))
    }

    @Test fun anUnknownToolIsOnlyCleaned() {
        assertEquals("Mcp github list issues", ToolLabels.label("mcp__github__list_issues"))
        assertEquals("Foo bar", ToolLabels.label("FooBar"))
        assertEquals("Deploy preview", ToolLabels.label("deploy-preview"))
        assertFalse(ToolLabels.label("some_odd_tool").contains('_'))
    }

    @Test fun aToolWithItsDetailKeepsTheDetail() {
        assertEquals("Running a command · npm test", ToolLabels.label("Bash · npm test"))
        assertEquals("Reading files", ToolLabels.label("Read · "))
    }

    @Test fun freeTextIsLeftAlone() {
        assertEquals("Running npm test", ToolLabels.label("Running npm test"))
        assertEquals("All done", ToolLabels.label("All done"))
        assertEquals("src/main.rs", ToolLabels.label("src/main.rs"))
        assertEquals("Build failed: 2 errors", ToolLabels.label("Build failed: 2 errors"))
    }

    @Test fun emptyStaysEmpty() {
        assertEquals("", ToolLabels.label(""))
        assertEquals("", ToolLabels.label("   "))
    }
}
