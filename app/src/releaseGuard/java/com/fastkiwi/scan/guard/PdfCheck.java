// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * An independent reader's verdict on a PDF the release build wrote: Apache PDFBox (desktop, not the
 * app's code) parses it without repairing the cross-reference table and renders every page.
 */
final class PdfCheck {
    private PdfCheck() {}

    static void assertValid(File f, int pages) throws Exception {
        assertTrue(f + " is empty", f.length() > 0);
        // Kept for a look after the run (build/releaseGuard/out).
        String keep = System.getProperty("kiwi.guard.out");
        if (keep != null) {
            File dir = new File(keep);
            dir.mkdirs();
            java.nio.file.Files.copy(f.toPath(), new File(dir, f.getName()).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] tail = new byte[(int) Math.min(64, r.length())];
            r.seek(r.length() - tail.length);
            r.readFully(tail);
            assertTrue(f + " does not end with %%EOF (stale or cut bytes at the end)", new String(tail, "ISO-8859-1").trim().endsWith("%%EOF"));
        }
        PDFParser parser = new PDFParser(new RandomAccessReadBufferedFile(f)) {{ setLenient(false); }};
        try (PDDocument d = parser.parse(false)) {
            assertEquals("pages in " + f, pages, d.getNumberOfPages());
            PDFRenderer renderer = new PDFRenderer(d);
            for (int i = 0; i < d.getNumberOfPages(); i++) renderer.renderImage(i, 0.25f);
        }
    }
}
