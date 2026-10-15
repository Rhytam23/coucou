package com.coucou.android.core

/**
 * Lets a notification be posted again only when what it shows has changed. Android treats every `notify` as an update that
 * redraws the shade (and may flicker, or wake the status bar), so a refresh that would look the same is skipped. [T] is the
 * value the notification is drawn from; it needs value equality.
 */
class PostOnChange<T> {
    private var shown: Any? = NOTHING

    /** True if [value] differs from what is on screen; remembers it as shown. */
    @Synchronized fun shouldPost(value: T): Boolean {
        if (shown == value) return false
        shown = value
        return true
    }

    /** The notification was posted by another path (the service's start) or the post failed: align or forget. */
    @Synchronized fun remember(value: T) { shown = value }
    @Synchronized fun forget() { shown = NOTHING }

    private companion object { val NOTHING = Any() }
}
