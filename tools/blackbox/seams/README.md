# Test seams (applied in 2.0-gates)

The two app-side seams the release-gate tooling needs are now in the app code; the original
proposals are kept here as patches for reference. Both are inert in the app: a nullable object
that only tests set.

## Camera seam (`ui/CaptureSeam.kt`, from `camera-capture-seam.patch`)

* **Why:** Robolectric has no CameraX, so the shutter's `capture()`, the status texts
  («أُضيفت الصفحة ١»), the live outline and the auto-capture gate could not run end to end.
* **What:** `CaptureSeam.camera: FakeCamera?` with `previewFrame()` and `takePicture()`. In
  `CameraScreen.capture()` a still from the fake goes through the same `addCapture` /
  `afterCapture` / status code; a `LaunchedEffect` feeds the fake's preview frames through
  `BitmapIO.detectPage`, the content signature (2.0-cam2, CAM-06) and the same `AutoCapture` gate.
* **Used by:** `gates/BlackBox.kt`. The driver serves `SyntheticPages.photo(cameraView, …)` frames
  (the same frame while the same thing is in view, i.e. a steady hold) and stills; the tester's
  shutter tap is a real tap on the button, and auto-capture is the app's own gate (the driver only
  holds the page still for up to 4 s and reports when no photo was taken).

## I/O faults (`data/IoFaults.kt`, from `io-faults.patch`)

* **Why:** the chaos tests could only make a write fail *before* it starts (a folder where a file
  must go). A full disk in the *middle* of a page or PDF write needs a hook in the write path.
* **What:** `IoFaults.hook: ((File, Long) -> Unit)?` and `wrap()`; `writeAtomically` (doc.json, page
  images, OCR cache) and the exporter's default `openStream` (the PDF and JPEG `.part` files) write
  through `IoFaults.wrap(...)`. The `Exporter.kt` hunk of the patch was adapted by hand: since 2.0-pdf
  the exporter opens its files through `openStream` / `SafeOutput`.
* **Used by:** `TortureChaosTest.diskFullMidWriteKeepsDocumentsIntact` (fails the write after 0 B,
  1 KB, 100 KB and 1 MB, for a page edit and for the PDF export).
