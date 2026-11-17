// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.gates

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import java.io.File

/**
 * What a screen reader (TalkBack) can see: every window in z-order, and in each the
 * merged semantics nodes with role, label, state, actions and on-screen bounds.
 */
object A11y {
    class Window(val view: View, val index: Int, val kind: String, val modal: Boolean, val dim: Float)

    class Node(
        val sn: SemanticsNode,
        val window: Window,
        val role: String,
        val label: String,
        val value: String?,
        val states: List<String>,
        val actions: List<String>,
        val bounds: Rect,
        val clickable: Boolean,
        val longClickable: Boolean,
        val scrollV: Boolean,
        val scrollH: Boolean,
        val editable: Boolean,
        val liveRegion: Boolean,
        val customActions: List<String>,
    ) {
        var index = 0
        val id get() = sn.id
        fun text(): String = listOfNotNull(label.takeIf { it.isNotEmpty() }, value?.let { "value \"$it\"" }).joinToString(" ")
    }

    @Suppress("UNCHECKED_CAST")
    fun windows(): List<Window> {
        val wmg = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val names = wmg.javaClass.getMethod("getViewRootNames").invoke(wmg) as Array<String>
        val views = names.mapNotNull { wmg.javaClass.getMethod("getRootView", String::class.java).invoke(wmg, it) as? View }
            .filter { it.isShown && it.width > 0 && it.height > 0 }
        if (views.isEmpty()) return emptyList()
        val base = views.first()
        return views.mapIndexed { i, v ->
            val lp = v.layoutParams as? WindowManager.LayoutParams
            val notFocusable = lp != null && (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0
            val dim = if (lp != null && (lp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) lp.dimAmount else 0f
            val kind = when {
                v === base -> "app window"
                v.javaClass.name.contains("Popup", true) || lp?.type == WindowManager.LayoutParams.TYPE_APPLICATION_PANEL || lp?.type == WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL -> "pop-up"
                else -> "dialog"
            }
            Window(v, i + 1, kind, modal = v !== base && !notFocusable, dim = dim)
        }
    }

    private fun composeRoots(v: View, out: MutableList<RootForTest>) {
        if (v is RootForTest) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) composeRoots(v.getChildAt(i), out)
    }

    /** All visible nodes of all windows, numbered in reading order per window. */
    fun snapshot(): List<Node> {
        val out = ArrayList<Node>()
        val screen = screenRect()
        for (w in windows()) {
            val roots = ArrayList<RootForTest>()
            composeRoots(w.view, roots)
            for (r in roots) {
                val rootView = r as View
                if (!rootView.isShown) continue
                val loc = IntArray(2)
                rootView.getLocationOnScreen(loc)
                walk(r.semanticsOwner.rootSemanticsNode, w, loc, screen, out)
            }
        }
        out.forEachIndexed { i, n -> n.index = i + 1 }
        return out
    }

    fun screenRect(): Rect {
        val dm = org.robolectric.RuntimeEnvironment.getApplication().resources.displayMetrics
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun hidden(sn: SemanticsNode): Boolean {
        for ((k, _) in sn.config) if (k.name == "InvisibleToUser" || k.name == "HideFromAccessibility") return true
        return false
    }

    private fun walk(sn: SemanticsNode, w: Window, loc: IntArray, screen: Rect, out: MutableList<Node>) {
        if (hidden(sn)) return
        val b = sn.boundsInRoot
        val rect = Rect((b.left + loc[0]).toInt(), (b.top + loc[1]).toInt(), (b.right + loc[0]).toInt(), (b.bottom + loc[1]).toInt())
        val visible = rect.width() > 0 && rect.height() > 0 && Rect.intersects(rect, screen)
        if (visible) describe(sn, w, rect)?.let { out.add(it) }
        // Children of an invisible (clipped) container are clipped too, but a zero-size
        // wrapper can still hold visible content.
        for (c in sn.children) walk(c, w, loc, screen, out)
    }

    private fun describe(sn: SemanticsNode, w: Window, rect: Rect): Node? {
        val c = sn.config
        val desc = c.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        val text = c.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        val edit = c.getOrNull(SemanticsProperties.EditableText)?.text
        val password = c.contains(SemanticsProperties.Password)
        val labelParts = (desc + text).map { it.replace('\n', ' ').trim() }.filter { it.isNotEmpty() }.distinct()
        val label = labelParts.joinToString(" · ")
        val clickable = c.contains(SemanticsActions.OnClick)
        val longClickable = c.contains(SemanticsActions.OnLongClick)
        val vScroll = c.contains(SemanticsProperties.VerticalScrollAxisRange)
        val hScroll = c.contains(SemanticsProperties.HorizontalScrollAxisRange)
        val editable = c.contains(SemanticsActions.SetText) || edit != null
        val live = c.contains(SemanticsProperties.LiveRegion)
        val custom = c.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
        val progress = c.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        val toggle = c.getOrNull(SemanticsProperties.ToggleableState)
        val selected = c.getOrNull(SemanticsProperties.Selected)
        val role = c.getOrNull(SemanticsProperties.Role)
        val heading = c.contains(SemanticsProperties.Heading)
        val pane = c.getOrNull(SemanticsProperties.PaneTitle)
        val collection = c.getOrNull(SemanticsProperties.CollectionInfo)
        val interesting = label.isNotEmpty() || edit != null || clickable || longClickable || vScroll || hScroll || progress != null || toggle != null || custom.isNotEmpty() || pane != null
        if (!interesting) return null
        val roleName = when {
            editable -> if (password) "password field" else "text field"
            role == Role.Button -> "button"
            role == Role.Checkbox -> "checkbox"
            role == Role.Switch -> "switch"
            role == Role.RadioButton -> "radio button"
            role == Role.Tab -> "tab"
            role == Role.Image -> "image"
            role == Role.DropdownList -> "drop-down list"
            role == Role.ValuePicker -> "value picker"
            heading -> "heading"
            progress != null -> "progress indicator"
            toggle != null -> "toggle"
            collection != null && (vScroll || hScroll) -> "list"
            vScroll || hScroll -> "scroll area"
            pane != null -> "pane"
            clickable -> "clickable"
            else -> "text"
        }
        val states = ArrayList<String>()
        when (toggle) {
            ToggleableState.On -> states.add("checked")
            ToggleableState.Off -> states.add("not checked")
            ToggleableState.Indeterminate -> states.add("partly checked")
            null -> Unit
        }
        if (selected == true) states.add("selected") else if (selected == false && (role == Role.Tab || role == Role.RadioButton)) states.add("not selected")
        if (c.contains(SemanticsProperties.Disabled)) states.add("disabled")
        if (c.getOrNull(SemanticsProperties.Focused) == true) states.add("focused")
        c.getOrNull(SemanticsProperties.StateDescription)?.let { states.add("state \"$it\"") }
        c.getOrNull(SemanticsProperties.Error)?.let { states.add("error \"$it\"") }
        if (progress != null) {
            if (progress.range.endInclusive > progress.range.start && progress.current >= progress.range.start) {
                val pct = ((progress.current - progress.range.start) / (progress.range.endInclusive - progress.range.start) * 100).toInt()
                states.add("progress $pct%")
            } else states.add("progress (busy)")
        }
        if (live) states.add("announces changes")
        if (pane != null) states.add("pane \"$pane\"")
        collection?.let { if (it.rowCount >= 0) states.add("${it.rowCount} items") }
        val actions = ArrayList<String>()
        if (clickable) actions.add(c.getOrNull(SemanticsActions.OnClick)?.label?.let { "tap ($it)" } ?: "tap")
        if (longClickable) actions.add(c.getOrNull(SemanticsActions.OnLongClick)?.label?.let { "long-press ($it)" } ?: "long-press")
        if (editable) actions.add("type")
        if (vScroll) actions.add("scroll up/down")
        if (hScroll) actions.add("scroll left/right")
        if (c.contains(SemanticsActions.Dismiss)) actions.add("dismiss")
        if (c.contains(SemanticsActions.Expand)) actions.add("expand")
        if (c.contains(SemanticsActions.Collapse)) actions.add("collapse")
        custom.forEach { actions.add("custom \"$it\"") }
        val value = if (edit != null) (if (password) "•".repeat(edit.length) else edit) else null
        return Node(sn, w, roleName, label, value, states, actions, rect, clickable, longClickable, vScroll, hScroll, editable, live, custom)
    }

    fun format(nodes: List<Node>, header: List<String>): String = buildString {
        header.forEach { appendLine(it) }
        var lastWindow = -1
        val topModal = nodes.map { it.window }.lastOrNull { it.modal }
        for (n in nodes) {
            if (n.window.index != lastWindow) {
                lastWindow = n.window.index
                val note = when {
                    topModal != null && n.window === topModal -> " (on top: only this layer can be used)"
                    topModal != null && n.window.index < topModal.index -> " (covered)"
                    else -> ""
                }
                appendLine("Layer ${n.window.index}: ${n.window.kind}$note")
            }
            append("  [${n.index}] ${n.role}")
            if (n.label.isNotEmpty()) append(" \"${n.label}\"")
            n.value?.let { append(" value=\"$it\"") }
            if (n.states.isNotEmpty()) append("  {${n.states.joinToString(", ")}}")
            if (n.actions.isNotEmpty()) append("  actions: ${n.actions.joinToString(", ")}")
            append("  bounds [${n.bounds.left},${n.bounds.top}][${n.bounds.right},${n.bounds.bottom}]")
            appendLine()
        }
    }

    /** Draws every window in z-order (dimming behind dialogs that ask for it) into one PNG. */
    fun screenshot(file: File) {
        val ws = windows()
        val screen = screenRect()
        val out = Bitmap.createBitmap(screen.width().coerceAtLeast(1), screen.height().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val loc = IntArray(2)
        for (w in ws) {
            if (w.dim > 0f) canvas.drawColor(Color.argb((w.dim * 255).toInt(), 0, 0, 0))
            w.view.getLocationOnScreen(loc)
            canvas.save()
            canvas.translate(loc[0].toFloat(), loc[1].toFloat())
            runCatching { w.view.draw(canvas) }
            canvas.restore()
        }
        file.parentFile?.mkdirs()
        file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        out.recycle()
    }
}
