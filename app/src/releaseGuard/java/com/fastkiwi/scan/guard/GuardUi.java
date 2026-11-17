// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.guard;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and taps the release app's Compose UI through its accessibility tree, the way TalkBack
 * does: by visible text only, never through app classes (their names are shrunk in the release).
 */
abstract class GuardUi {
    static final String PKG = "com.fastkiwi.scan";

    Application app;
    Activity activity;

    String str(String name) {
        int id = app.getResources().getIdentifier(name, "string", PKG);
        assertTrue("string resource missing from the release: " + name, id != 0);
        return app.getString(id);
    }

    static void settle(long ms) {
        long end = System.currentTimeMillis() + ms;
        // The main looper is paused: the clock moves one frame at a time (Compose animations run on it),
        // and real time passes so background work (the export itself) can finish.
        do {
            ShadowLooper.idleMainLooper(16, java.util.concurrent.TimeUnit.MILLISECONDS);
            try { Thread.sleep(4); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        } while (System.currentTimeMillis() < end);
        ShadowLooper.idleMainLooper();
    }

    void tap(String text) {
        Node n = find(text);
        assertNotNull("\"" + text + "\" is not on the screen. Screen: " + screenText(), n);
        n.click();
        settle(300);
    }

    Node find(String text) {
        // An exact label first ("Guard doc", not "Add “Guard doc” to favorites").
        for (Node n : nodes()) {
            if (!n.label().equals(text)) continue;
            Node c = n.clickableSelfOrAncestor();
            if (c != null) return c;
        }
        Node best = null;
        for (Node n : nodes()) {
            String l = n.label();
            if (!l.contains(text)) continue;
            Node c = n.clickableSelfOrAncestor();
            if (c != null) return c;
            if (best == null) best = n;
        }
        return best;
    }

    String screenText() {
        StringBuilder sb = new StringBuilder();
        for (Node n : nodes()) {
            String l = n.label();
            if (!l.isEmpty()) sb.append('[').append(l.replace('\n', ' ')).append("] ");
        }
        return sb.toString();
    }

    List<Node> nodes() {
        List<View> roots = new ArrayList<>();
        for (Dialog d : ShadowDialog.getShownDialogs()) if (d.isShowing() && d.getWindow() != null) roots.add(d.getWindow().getDecorView());
        roots.add(activity.getWindow().getDecorView());
        List<Node> out = new ArrayList<>();
        for (View r : roots) collectViews(r, out);
        return out;
    }

    static void collectViews(View v, List<Node> out) {
        AccessibilityNodeProvider p = v.getAccessibilityNodeProvider();
        if (p != null) {
            walk(p, AccessibilityNodeProvider.HOST_VIEW_ID, null, out, 0);
            return;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectViews(g.getChildAt(i), out);
        }
    }
    static Method childId, virtualId;

    static void walk(AccessibilityNodeProvider p, int id, Node parent, List<Node> out, int depth) {
        if (depth > 60) return;
        AccessibilityNodeInfo info = p.createAccessibilityNodeInfo(id);
        if (info == null) return;
        Node n = new Node(p, id, info, parent);
        out.add(n);
        try {
            if (childId == null) {
                childId = AccessibilityNodeInfo.class.getDeclaredMethod("getChildId", int.class);
                virtualId = AccessibilityNodeInfo.class.getDeclaredMethod("getVirtualDescendantId", long.class);
                childId.setAccessible(true);
                virtualId.setAccessible(true);
            }
            for (int i = 0; i < info.getChildCount(); i++) {
                long cid = (Long) childId.invoke(info, i);
                int vid = (Integer) virtualId.invoke(null, cid);
                walk(p, vid, n, out, depth + 1);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static final class Node {
        final AccessibilityNodeProvider provider;
        final int id;
        final AccessibilityNodeInfo info;
        final Node parent;

        Node(AccessibilityNodeProvider provider, int id, AccessibilityNodeInfo info, Node parent) {
            this.provider = provider; this.id = id; this.info = info; this.parent = parent;
        }

        String label() {
            StringBuilder sb = new StringBuilder();
            if (info.getText() != null) sb.append(info.getText());
            if (info.getContentDescription() != null) { if (sb.length() > 0) sb.append(' '); sb.append(info.getContentDescription()); }
            return sb.toString();
        }

        Node clickableSelfOrAncestor() {
            for (Node n = this; n != null; n = n.parent) if (n.info.isClickable() && n.info.isEnabled()) return n;
            return null;
        }

        void click() {
            assertTrue("tap on \"" + label() + "\" was refused (disabled?)", provider.performAction(id, AccessibilityNodeInfo.ACTION_CLICK, (Bundle) null));
        }
    }

    /** Taps [text], scrolling the screen's lists (each in turn, the tallest first) until it shows up. */
    void tapScrolling(String text) {
        List<Node> lists = new ArrayList<>();
        for (Node n : nodes()) if (n.info.isScrollable()) lists.add(n);
        lists.sort((a, b) -> Integer.compare(height(b), height(a)));
        for (Node list : lists) {
            if (find(text) != null) break;
            // From the top, then down.
            for (int i = 0; i < 12; i++) {
                if (!list.provider.performAction(list.id, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, (Bundle) null)) break;
                settle(200);
            }
            for (int i = 0; i < 12 && find(text) == null; i++) {
                if (!list.provider.performAction(list.id, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, (Bundle) null)) break;
                settle(300);
            }
            if (find(text) != null) break;
        }
        tap(text);
    }

    private static int height(Node n) {
        android.graphics.Rect r = new android.graphics.Rect();
        n.info.getBoundsInScreen(r);
        return r.height();
    }
}
