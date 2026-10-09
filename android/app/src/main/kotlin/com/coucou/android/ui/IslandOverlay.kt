package com.coucou.android.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.coucou.android.R
import com.coucou.android.core.OverlayPolicy
import com.coucou.android.link.ApprovalRequest
import com.coucou.android.mochi.BotState
import com.coucou.android.mochi.MochiEngine
import com.coucou.android.mochi.MochiView
import kotlin.math.min

/**
 * The notch of the desktop, for the phone: a small black pill that drops from the top of the screen
 * over whatever app is open when an agent needs you or finishes, then slides away. A permission
 * request stays, expanded, until it is answered.
 *
 * Needs the user to allow "display over other apps" once. Nothing exists while it is hidden: the
 * window is added when something happens and removed after the pill has slid away, so it costs no
 * CPU the rest of the time. Allow opens the app for the fingerprint check; Deny acts right here.
 */
class IslandOverlay(
    private val context: Context,
    private val clock: () -> Double,
    private val onAllow: (ApprovalRequest) -> Unit,
    private val onDeny: (ApprovalRequest) -> Unit,
    private val onOpen: () -> Unit,
) {
    private sealed interface Content {
        data class Status(val agent: String, val state: BotState, val text: String) : Content
        data class Approval(val request: ApprovalRequest, val agent: String) : Content
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var content by mutableStateOf<Content?>(null)
    private var transition = MutableTransitionState(false)
    private var view: ComposeView? = null
    private var owner: Owner? = null
    private val slideAwayLater = Runnable { slideAway() }
    private val removeLater = Runnable { removeView() }

    fun permitted(): Boolean = Settings.canDrawOverlays(context)

    /** A short notice (finished, failed, asking…). Never replaces a request waiting for an answer. */
    fun showStatus(agent: String, state: BotState, text: String) = safely {
        if (content is Content.Approval) return@safely
        if (!present(Content.Status(agent, state, text))) return@safely
        main.removeCallbacks(slideAwayLater)
        main.postDelayed(slideAwayLater, OverlayPolicy.STATUS_MS)
    }

    /** A permission request: stays until it is answered, withdrawn or expired. */
    fun showApproval(request: ApprovalRequest, agent: String) = safely {
        main.removeCallbacks(slideAwayLater)
        present(Content.Approval(request, agent))
    }

    /** The request was answered elsewhere, withdrawn or expired. */
    fun hideApproval(fingerprint: String) = safely {
        if ((content as? Content.Approval)?.request?.fingerprint == fingerprint) slideAway()
    }

    fun hide() = safely { slideAway() }

    /**
     * The pill is a nicety on top of the real app: whatever goes wrong with the window (permission
     * taken away while it shows, a bad token, a SecurityException) must never reach the caller.
     */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w("CoucouOverlay", "overlay failed: ${e.javaClass.simpleName}")
            runCatching { removeView() }
        }
    }

    private fun present(c: Content): Boolean {
        if (!permitted()) return false
        main.removeCallbacks(removeLater)
        content = c
        ensureView()
        transition.targetState = true
        return true
    }

    private fun slideAway() {
        main.removeCallbacks(slideAwayLater)
        if (view == null) return
        transition.targetState = false
        main.removeCallbacks(removeLater)
        main.postDelayed(removeLater, 450)
    }

    private fun removeView() {
        if (transition.targetState) return // something new arrived meanwhile
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        owner?.destroy()
        owner = null
        content = null
    }

    private fun ensureView() {
        if (view != null) return
        val density = context.resources.displayMetrics.density
        val width = min(context.resources.displayMetrics.widthPixels - (24 * density).toInt(), (380 * density).toInt())
        val statusBar = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }?.let { context.resources.getDimensionPixelSize(it) } ?: (24 * density).toInt()
        val params = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable and not modal: touches outside the pill go to the app underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBar + (2 * density).toInt()
        }
        val lifecycleOwner = Owner()
        transition = MutableTransitionState(false) // enters from outside the screen
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setContent { Pill() }
        }
        try {
            wm.addView(v, params)
        } catch (e: Exception) {
            Log.w("CoucouOverlay", "cannot add the window: ${e.javaClass.simpleName}")
            lifecycleOwner.destroy() // the permission was taken away meanwhile
            return
        }
        owner = lifecycleOwner
        view = v
    }

    // ── What is drawn ────────────────────────────────────────────────────────

    private val ink = Color(0xFF000000)
    private val dim = Color(0xFFA1A6B0)
    private val line = Color(0xFF26282E)
    private val accent = Color(0xFF8AB4FF)

    @Composable
    private fun Pill() {
        AnimatedVisibility(
            visibleState = transition,
            enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) { -it * 2 } + fadeIn(tween(150)),
            exit = slideOutVertically(tween(260)) { -it * 2 } + fadeOut(tween(220)),
        ) {
            when (val c = content) {
                is Content.Status -> StatusPill(c)
                is Content.Approval -> ApprovalPill(c)
                null -> {}
            }
        }
    }

    @Composable
    private fun StatusPill(c: Content.Status) {
        val engine = remember(c.state) { MochiEngine(clock).apply { setState(c.state, force = true) } }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(ink)
                .clickable { onOpen() }.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MochiView(engine, Modifier.size(40.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(c.agent, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (c.text.isNotBlank()) Text(c.text, color = dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    @Composable
    private fun ApprovalPill(c: Content.Approval) {
        val engine = remember { MochiEngine(clock).apply { setState(BotState.APPROVAL, force = true) } }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(ink).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.clickable { onOpen() }, verticalAlignment = Alignment.CenterVertically) {
                MochiView(engine, Modifier.size(40.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.agent, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(c.request.tool, color = accent, fontSize = 12.sp, maxLines = 1)
                }
            }
            Text(
                c.request.command,
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF16171B)).padding(10.dp),
                color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onDeny(c.request) }, Modifier.weight(1f).height(44.dp), shape = CircleShape,
                    border = BorderStroke(1.dp, line),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) { Text(stringResource(R.string.action_deny)) }
                Button(
                    onClick = { onAllow(c.request) }, Modifier.weight(1f).height(44.dp), shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black),
                ) { Text(stringResource(R.string.action_allow)) }
            }
        }
    }

    /** A window outside any activity has no lifecycle of its own: Compose needs one to run. */
    private class Owner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
        private val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

        init {
            controller.performAttach()
            controller.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}
