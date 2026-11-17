// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 2.0.1 release guard: drives the R8-minified release code (not the debug classes the other 327
 * tests use) through Export -> Share / Save, the way a user taps it. Scan Kiwi 2.0.0 shipped a
 * release build in which Export and Share did nothing on a real phone while every debug test passed.
 *
 * <p>Runs only in {@code :app:releaseGuardTest}: its classpath is the release program shrunk by the
 * same R8 version and the same merged rules as {@code minifyReleaseWithR8} (written as class files
 * instead of dex), the release resources and assets, and Robolectric. App code is reached only
 * through what the release keeps by name (the activity, the manifest, resource names), never through
 * app classes, so a rename or removal by R8 shows up here as it would on a phone. Written in Java so
 * that no un-shrunk Kotlin runtime sits beside the shrunk one.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Android 8 to 16. (Android 7, the minimum, is left out: Robolectric 4.16 gives Compose no accessibility
// tree there, which this test reads the screen through.)
@Config(sdk = {26, 28, 29, 31, 33, 34, 36}, qualifiers = "ar-w400dp-h860dp-xxhdpi")
public class ReleaseExportGuardTest extends GuardUi {
    private static final String DOC_NAME = "Guard doc";
    private static final long WAIT_MS = 120_000;
    private static final int PAGES = 3;
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    private ActivityController<Activity> controller;

    @Before
    public void setUp() throws Exception {
        app = (Application) org.robolectric.RuntimeEnvironment.getApplication();
        // Compose answers accessibility queries (our way to read and tap the UI) only with a service on.
        AccessibilityManager am = (AccessibilityManager) app.getSystemService(Context.ACCESSIBILITY_SERVICE);
        org.robolectric.shadows.ShadowAccessibilityManager sam = shadowOf(am);
        sam.setEnabled(true);
        sam.setTouchExplorationEnabled(true);
        sam.setEnabledAccessibilityServiceList(Collections.singletonList(new AccessibilityServiceInfo()));
        writeDocument(app.getFilesDir());
        // Older Android versions run in low-memory mode (pages and OCR at most 2000 px), as on small phones.
        if (android.os.Build.VERSION.SDK_INT <= 29) {
            app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("low_memory_mode", "ON").commit();
        }
        @SuppressWarnings("unchecked")
        Class<Activity> main = (Class<Activity>) Class.forName(PKG + ".MainActivity");
        controller = Robolectric.buildActivity(main).setup();
        activity = controller.get();
        settle(1500);
        // First open: the one-time notice about the update check.
        Node ok = find(str("update_notice_ok"));
        if (ok != null) { ok.click(); settle(500); }
    }

    @After
    public void tearDown() {
        if (controller != null) controller.pause().stop().destroy();
    }

    /**
     * One activity, every path a user takes from the export sheet: TXT, PDF and JPG to the share sheet,
     * then PDF to "Save as". (One test on purpose: the app's Compose UI is driven without a test rule,
     * and a second activity in the same Robolectric sandbox does not take taps.)
     */
    @Test
    public void exportShareAndSaveWorkInTheReleaseBuild() throws Exception {
        openExportSheet();

        tap(str("format_txt"));
        tap(str("share"));
        // Without an OCR engine on the JVM the text may be empty; the app must then say why, never stay silent.
        Intent txt = awaitShareOrMessage("text/plain");
        if (txt != null) assertSharedFile(txt, ".txt", null);

        tap("PDF");
        tap(str("share"));
        File pdf = shared("application/pdf", ".pdf", "%PDF-");
        PdfCheck.assertValid(pdf, PAGES);

        tap(str("format_jpg_images"));
        tap(str("share"));
        shared("image/jpeg", ".jpg", new String(new char[] {0xFF, 0xD8}));

        tap("PDF");
        tap(str("save_to_device"));
        ShadowActivity sa = shadowOf(activity);
        ShadowActivity.IntentForResult req = null;
        long end = System.currentTimeMillis() + 10_000;
        while (req == null && System.currentTimeMillis() < end) { settle(100); req = sa.getNextStartedActivityForResult(); }
        assertNotNull("Save opened no system \"Save as\" dialog. Screen: " + screenText(), req);
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, req.intent.getAction());
        File target = new File(app.getCacheDir(), "saved-by-user.pdf");
        // A longer file already there: the save must replace it, never leave old bytes at the end.
        try (FileOutputStream o = new FileOutputStream(target)) { o.write(new byte[4 << 20]); }
        Intent data = new Intent().setData(Uri.fromFile(target));
        sa.receiveResult(req.intent, Activity.RESULT_OK, data);
        // Done when the export is no longer running and the chosen file was replaced (the confirmation
        // snackbar may wait behind an earlier one, so it is not what this waits for).
        String failed = str("msg_export_failed").split("%")[0].trim();
        end = System.currentTimeMillis() + WAIT_MS;
        settle(500);
        while (System.currentTimeMillis() < end && (exportRunning() || target.length() == (4 << 20))) settle(200);
        assertNull("Save failed: " + screenText(), find(failed));
        PdfCheck.assertValid(target, PAGES);
        // The sheet itself confirms (the snackbar is under it).
        String saved = str("msg_saved").split("%")[0].trim();
        end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end && find(saved) == null) settle(200);
        assertNotNull("no «saved» confirmation in the sheet. Screen: " + screenText(), find(saved));

        // The same PDF with the options that change how pages are made: hidden text (OCR; on the JVM the
        // engine is missing, so the app must say so and still write every page), page numbers and a
        // target size (several passes into the same file).
        tapScrolling(str("ocr_title"));
        tapScrolling(str("pdf_numbers_title"));
        tapScrolling(str("pdf_quality_target"));
        tap(str("share"));
        PdfCheck.assertValid(shared("application/pdf", ".pdf", "%PDF-"), PAGES);
    }

    // ------------------------------------------------------------------ steps

    private void openExportSheet() {
        // The list can take a moment to accept taps after start: tap until the editor is open.
        long until = System.currentTimeMillis() + 15_000;
        do {
            tap(DOC_NAME);
            settle(800);
        } while (find(str("export")) == null && System.currentTimeMillis() < until);
        // The editor's Export button (the sheet then shows a title with the same word).
        tap(str("export"));
        settle(800);
        for (int i = 0; i < 10 && find(str("share")) == null; i++) {
            settle(500);
            System.out.println("GUARD after export " + i + ": dialogs=" + ShadowDialog.getShownDialogs().size() + " screen=" + screenText());
        }
        assertNotNull("Export opened no sheet (no Share button). Screen: " + screenText(), find(str("share")));
    }

    /** The shared file (a copy read through our FileProvider), or on a Windows host the file the app made. */
    private File shared(String mime, String ext, String magic) throws Exception {
        Intent inner = awaitShareOrMessage(mime);
        if (inner != null) return assertSharedFile(inner, ext, magic);
        assertTrue("Share opened no share sheet. Screen: " + screenText(), WINDOWS);
        File newest = null;
        File[] all = new File(app.getCacheDir(), "exports").listFiles();
        if (all != null) for (File f : all) if (f.getName().endsWith(ext) && (newest == null || f.lastModified() >= newest.lastModified())) newest = f;
        assertNotNull("no " + ext + " export was written", newest);
        return newest;
    }

    /** The share sheet's inner intent, or null when the app said something instead (asserted). */
    private Intent awaitShareOrMessage(String mime) {
        long end = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < end) {
            settle(200);
            Intent i;
            while ((i = shadowOf(app).getNextStartedActivity()) != null || (i = shadowOf(activity).getNextStartedActivity()) != null) {
                if (Intent.ACTION_CHOOSER.equals(i.getAction())) {
                    Intent inner = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
                    assertNotNull("chooser without an inner intent", inner);
                    assertEquals(mime, inner.getType());
                    return inner;
                }
            }
            if (exportRunning()) continue;
            // Notes such as "no OCR on this phone, saved without searchable text" are not failures.
            String said = mime.equals("text/plain") ? message() : failure();
            if (said != null) {
                // androidx FileProvider compares paths with '/', so on a Windows host (local runs) it cannot
                // make a URI for any file; Linux (CI) and phones get the share sheet. The file is then
                // checked where the app wrote it, and the app must have said so in words.
                if (WINDOWS && said.contains("Failed to find configured root")) return null;
                if (mime.equals("text/plain")) return null;
                fail("Share showed a message instead of the share sheet: " + said);
            }
        }
        StringBuilder made = new StringBuilder();
        File[] all = new File(app.getCacheDir(), "exports").listFiles();
        if (all != null) for (File f : all) made.append(f.getName()).append(' ').append(f.length()).append("; ");
        fail("Share did nothing for " + WAIT_MS / 1000 + " s (no share sheet, no message). Exports: " + made + " Screen: " + screenText());
        return null;
    }

    /** Checks the share intent and returns a copy of the shared file, read through our FileProvider. */
    private File assertSharedFile(Intent inner, String ext, String magic) throws Exception {
        Uri uri = (Uri) inner.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) {
            ArrayList<Uri> all = inner.<Uri>getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            assertTrue("share intent without a file", all != null && !all.isEmpty());
            uri = all.get(0);
        }
        assertEquals("content", uri.getScheme());
        assertEquals(PKG + ".files", uri.getAuthority());
        assertTrue("read permission not granted to the receiving app", (inner.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertTrue(uri + " is not a " + ext, uri.getPath().endsWith(ext));
        // The receiving app reads it through our FileProvider (paths from res/xml/file_paths in the release resources).
        File copy = File.createTempFile("shared", ext);
        try (InputStream in = app.getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(copy)) {
            assertNotNull("FileProvider returned no stream for " + uri, in);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        assertTrue("shared file is empty", copy.length() > 0);
        if (magic != null) {
            byte[] head = new byte[magic.length()];
            try (InputStream in = new java.io.FileInputStream(copy)) { assertEquals(head.length, in.read(head)); }
            assertEquals(magic, new String(head, "ISO-8859-1"));
        }
        return copy;
    }

    // ------------------------------------------------------------------ the document

    /**
     * Three pages in the store's on-disk format (filesDir/documents/<id>/doc.json, and per page the photo,
     * the rendered page and its thumbnail, as a capture leaves them); page 2 is black and white.
     */
    private static void writeDocument(File filesDir) throws Exception {
        String docId = "a1b2c3d4e5f60718";
        String[] pageIds = {"0f1e2d3c4b5a6978", "1f1e2d3c4b5a6978", "2f1e2d3c4b5a6978"};
        File dir = new File(new File(filesDir, "documents"), docId);
        assertTrue(dir.mkdirs() || dir.isDirectory());
        JSONArray pages = new JSONArray();
        for (int k = 0; k < pageIds.length; k++) {
            // Different sizes, so the export's reused decode bitmap has to change shape.
            Bitmap b = Bitmap.createBitmap(900 + 300 * k, 1200 + 200 * k, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(Color.WHITE);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.BLACK);
            p.setTextSize(48f);
            for (int y = 150; y < b.getHeight() - 100; y += 90) c.drawText("Invoice 2026 total 1250 page " + (k + 1), 80, y, p);
            for (String suffix : new String[] {"", "_out", "_thumb"}) {
                try (FileOutputStream out = new FileOutputStream(new File(dir, "p_" + pageIds[k] + suffix + ".jpg"))) {
                    b.compress(Bitmap.CompressFormat.JPEG, 90, out);
                }
            }
            pages.put(new JSONObject().put("id", pageIds[k])
                .put("corners", new JSONArray("[0,0,1,0,1,1,0,1]"))
                .put("rotation", 0).put("filter", k == 1 ? "BW" : "ORIGINAL").put("revision", 0).put("stamps", new JSONArray()));
        }
        long now = System.currentTimeMillis();
        JSONObject doc = new JSONObject().put("version", 1).put("id", docId).put("name", DOC_NAME)
            .put("created", now).put("modified", now).put("pages", pages).put("favorite", false);
        try (FileOutputStream out = new FileOutputStream(new File(dir, "doc.json"))) {
            out.write(doc.toString().getBytes("UTF-8"));
        }
    }

    // ------------------------------------------------------------------ UI through accessibility




    private boolean exportRunning() {
        return find(str("cancel")) != null && find(str("share")) == null;
    }

    /** A snackbar or error line that is not part of the export sheet. */
    /** An export failure on the screen («تعذّر التصدير: …»), or null. */
    private String failure() {
        for (String key : new String[] {"msg_export_failed", "msg_error"}) {
            String t = str(key).split("%")[0].trim();
            Node n = find(t);
            if (n != null) return n.label();
        }
        return null;
    }

    private String message() {
        for (String key : new String[] {"msg_export_failed", "msg_error", "msg_ocr_unavailable", "pdf_msg_no_text", "docs_txt_empty"}) {
            int id = app.getResources().getIdentifier(key, "string", PKG);
            if (id == 0) continue;
            String s = app.getString(id).split("%")[0].trim();
            if (s.isEmpty()) continue;
            Node n = find(s);
            if (n != null) return n.label();
        }
        return null;
    }





}
