# Proposed test seams (not applied)

The release-gate tooling adds only test-side files. Two things it needs cannot be done
without a small change in app code; the changes are here as patches for the lead to apply
after the 2.0 feature branches are merged (`git apply tools/blackbox/seams/<name>.patch`,
then re-run `tools/blackbox/torture.sh`). Both are inert in the app: a nullable object that
only tests set. Both were checked with `git apply --check` against `release/2.0.0` @ 487c25b;
CameraScreen.kt, Exporter.kt and AtomicWrite.kt are touched by the Camera, PDF and Documents
branches, so expect to re-apply the small hunks by hand.

## camera-capture-seam.patch

* **Why:** Robolectric has no CameraX. Today the black-box driver simulates the camera by
  calling `AppViewModel.addCapture` + `afterCapture` itself, exactly what the capture
  callback does. That skips CameraScreen's own code: the shutter's `capture()`, the
  `capturing`/status texts («أُضيفت الصفحة ١»), the live outline and the auto-capture gate
  fed by preview frames.
* **What:** new `ui/CaptureSeam.kt` (`CaptureSeam.camera: FakeCamera?` with
  `previewFrame()` and `takePicture()`); in `CameraScreen.capture()` a still from the fake
  goes through the same `addCapture`/`afterCapture`/status code; a `LaunchedEffect` feeds
  fake preview frames through `BitmapIO.detectPage` into the same `AutoCapture` gate.
* **Then:** in `BlackBox.shoot()` and `autoCapture()`, set `CaptureSeam.camera` to a fake
  serving `SyntheticPages.photo(cameraView, …)` and tap the real shutter instead of calling
  the view model.

## io-faults.patch

* **Why:** the chaos tests can only make a write fail *before* it starts (a folder where a
  file must go). A full disk in the *middle* of a page or PDF write (ENOSPC after N bytes)
  needs a hook in the write path.
* **What:** new `data/IoFaults.kt` (`IoFaults.hook: ((File, Long) -> Unit)?` and `wrap()`);
  `writeAtomically` (doc.json, page images, OCR cache) and the exporter's `.part` PDF and
  JPEG outputs write through `IoFaults.wrap(...)`.
* **Then:** add a chaos test: for N in 0, 1 KB, 100 KB, 1 MB, fail the Nth byte of (a) a
  page save, (b) doc.json, (c) the PDF export; check the document is intact, no `.tmp`/`.part`
  file is exposed and an error message is shown.
