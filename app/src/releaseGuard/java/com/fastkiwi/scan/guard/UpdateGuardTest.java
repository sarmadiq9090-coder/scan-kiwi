// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.guard;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.robolectric.Shadows.shadowOf;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.view.accessibility.AccessibilityManager;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowNetworkCapabilities;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Collections;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * 2.0.1 release guard for the in-app updater (used from 2.0.x on). The shrunk release code checks a
 * local fake GitHub: HTTPS with a test certificate, the real host names mapped to 127.0.0.1 through
 * {@code jdk.net.hosts.file}. It parses the release JSON, reads SHA256SUMS.txt, follows GitHub's
 * redirect, downloads the APK and compares its sha256, as on a phone. Android's own signature check
 * of the archive and PackageInstaller have no real counterpart on the JVM, so the download must get
 * past the network and checksum steps: to "ready", or to the signature verdict.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 36, qualifiers = "en-w400dp-h860dp-xxhdpi")
public class UpdateGuardTest extends GuardUi {
    private static final String OWNER_REPO = "sarmadiq9090-coder/scan-kiwi";
    private static final String TAG = "v99.0.0";
    private static final String APK_NAME = "ScanKiwi-99.0.0.apk";

    private HttpsServer server;
    private ActivityController<Activity> controller;
    private volatile int apiHits, sumsHits, apkHits;

    @Before
    public void setUp() throws Exception {
        String ks = System.getProperty("kiwi.guard.keystore");
        assumeTrue("no test certificate (run through :app:releaseGuardTest)", ks != null && new File(ks).isFile());
        byte[] apk = Files.readAllBytes(new File(System.getProperty("kiwi.guard.apk")).toPath());
        String sums = hex(MessageDigest.getInstance("SHA-256").digest(apk)) + "  " + APK_NAME + "\n";
        try {
            server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 443), 0);
        } catch (IOException e) {
            assumeTrue("port 443 cannot be opened here: " + e, false);
        }
        char[] pw = "guard-only".toCharArray();
        KeyStore k = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(ks)) {
            k.load(in, pw);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(k, pw);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        String download = "https://github.com/" + OWNER_REPO + "/releases/download/" + TAG + "/";
        String json = "{\"tag_name\":\"" + TAG + "\",\"draft\":false,\"prerelease\":false,\"body\":\"test\",\"assets\":["
            + "{\"name\":\"" + APK_NAME + "\",\"size\":" + apk.length + ",\"browser_download_url\":\"" + download + APK_NAME + "\"},"
            + "{\"name\":\"SHA256SUMS.txt\",\"size\":" + sums.length() + ",\"browser_download_url\":\"" + download + "SHA256SUMS.txt\"}]}";
        server.createContext("/", ex -> {
            String host = String.valueOf(ex.getRequestHeaders().getFirst("Host")).replace(":443", "");
            String path = ex.getRequestURI().getPath();
            byte[] body;
            int code = 200;
            if (host.equals("api.github.com") && path.equals("/repos/" + OWNER_REPO + "/releases/latest")) {
                apiHits++;
                body = json.getBytes(StandardCharsets.UTF_8);
            } else if (host.equals("github.com") && path.startsWith("/" + OWNER_REPO + "/releases/download/" + TAG + "/")) {
                // Like GitHub: a redirect to the asset host.
                ex.getResponseHeaders().add("Location", "https://release-assets.githubusercontent.com/a/" + path.substring(path.lastIndexOf('/') + 1));
                ex.sendResponseHeaders(302, -1);
                ex.close();
                return;
            } else if (host.equals("release-assets.githubusercontent.com") && path.equals("/a/SHA256SUMS.txt")) {
                sumsHits++;
                body = sums.getBytes(StandardCharsets.UTF_8);
            } else if (host.equals("release-assets.githubusercontent.com") && path.equals("/a/" + APK_NAME)) {
                apkHits++;
                body = apk;
            } else {
                code = 404;
                body = "not found".getBytes(StandardCharsets.UTF_8);
            }
            ex.sendResponseHeaders(code, body.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(body);
            }
        });
        server.start();

        app = (Application) org.robolectric.RuntimeEnvironment.getApplication();
        AccessibilityManager am = (AccessibilityManager) app.getSystemService(Context.ACCESSIBILITY_SERVICE);
        org.robolectric.shadows.ShadowAccessibilityManager sam = shadowOf(am);
        sam.setEnabled(true);
        sam.setTouchExplorationEnabled(true);
        sam.setEnabledAccessibilityServiceList(Collections.singletonList(new AccessibilityServiceInfo()));
        // The app's updater stays offline under Robolectric (fingerprint "robolectric") so that no test
        // reaches the real GitHub; this one is the exception, pointed at the local fake.
        org.robolectric.shadows.ShadowBuild.setFingerprint("kiwi/release-guard");
        // On Wi-Fi, with internet.
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities nc = ShadowNetworkCapabilities.newInstance();
        shadowOf(nc).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        shadowOf(nc).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        shadowOf(nc).addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        shadowOf(cm).setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, android.net.NetworkInfo.State.CONNECTED));
        android.net.Network net = cm.getActiveNetwork();
        assertNotNull("no active network in the test environment", net);
        shadowOf(cm).setNetworkCapabilities(net, nc);

        @SuppressWarnings("unchecked")
        Class<Activity> main = (Class<Activity>) Class.forName(PKG + ".MainActivity");
        controller = Robolectric.buildActivity(main).setup();
        activity = controller.get();
        settle(1500);
        Node ok = find(str("update_notice_ok"));
        if (ok != null) {
            ok.click();
            settle(500);
        }
    }

    @After
    public void tearDown() {
        if (controller != null) controller.pause().stop().destroy();
        if (server != null) server.stop(0);
    }

    @Test
    public void checkFindsTheReleaseAndTheDownloadPassesItsChecksum() {
        tap(str("settings"));
        settle(800);
        tapScrolling(str("updates_title"));
        settle(800);
        tap(str("update_check_now"));
        String available = str("update_available").replace("%1$s", "99.0.0");
        long end = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < end && find(available) == null) settle(200);
        assertNotNull("the update check did not offer 99.0.0 (release JSON requests: " + apiHits + "). Screen: " + screenText(), find(available));

        tap(str("update_download_install"));
        String ready = str("update_ready");
        String signature = str("update_err_signature");
        end = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < end && find(ready) == null && find(signature) == null) settle(200);
        assertTrue("SHA256SUMS.txt was not read", sumsHits > 0);
        assertTrue("the APK was not downloaded", apkHits > 0);
        assertTrue("the download stopped before the signature step (network or checksum). Screen: " + screenText(),
            find(ready) != null || find(signature) != null);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }
}
