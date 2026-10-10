package com.coucou.android.core

/** The three places the bottom bar leads to. */
enum class Tab { HOME, CHAT, SETTINGS }

/** Every screen of the app. Tabs are the roots; the rest are one level down. */
enum class Screen { HOME, CHAT, SETTINGS, HISTORY, GALLERY, DESIGN, SESSION, SCAN }

/**
 * Where the user is and where Back goes (android/UX_PLAN.md, section 2). Pure, so every rule is a unit test:
 * - the bar has Home and Settings, plus Chat only while the computer offers it or there is a conversation to read;
 * - the bar shows on the three tab roots, and hides on every page below them and while the keyboard is open;
 * - Back goes up one level; on Home it is the system's (leave the app).
 */
object Nav {
    fun tabs(chatOffered: Boolean, hasConversation: Boolean): List<Tab> =
        buildList {
            add(Tab.HOME)
            if (chatOffered || hasConversation) add(Tab.CHAT)
            add(Tab.SETTINGS)
        }

    fun screenOf(tab: Tab): Screen = when (tab) {
        Tab.HOME -> Screen.HOME
        Tab.CHAT -> Screen.CHAT
        Tab.SETTINGS -> Screen.SETTINGS
    }

    /** The tab a screen belongs to, so the bar can highlight it; null for full-screen tasks. */
    fun tabOf(screen: Screen): Tab? = when (screen) {
        Screen.HOME -> Tab.HOME
        Screen.CHAT -> Tab.CHAT
        Screen.SETTINGS, Screen.HISTORY, Screen.GALLERY, Screen.DESIGN -> Tab.SETTINGS
        Screen.SESSION, Screen.SCAN -> null
    }

    fun barVisible(screen: Screen, keyboardOpen: Boolean): Boolean =
        !keyboardOpen && screen in setOf(Screen.HOME, Screen.CHAT, Screen.SETTINGS)

    /** One level up, or null when Back should leave the app. */
    fun back(screen: Screen): Screen? = when (screen) {
        Screen.HOME -> null
        Screen.CHAT, Screen.SETTINGS, Screen.SESSION, Screen.SCAN -> Screen.HOME
        Screen.HISTORY, Screen.GALLERY -> Screen.SETTINGS
        Screen.DESIGN -> Screen.GALLERY
    }

    /** The chat tab can vanish (the computer stopped offering it): never leave the user on a screen with no tab. */
    fun resolve(screen: Screen, tabs: List<Tab>): Screen {
        val tab = tabOf(screen) ?: return screen
        return if (tab in tabs) screen else Screen.HOME
    }
}
