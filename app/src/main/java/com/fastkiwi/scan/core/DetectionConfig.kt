// SPDX-FileCopyrightText: 2026 FastKiwi
// SPDX-License-Identifier: Apache-2.0
package com.fastkiwi.scan.core

/**
 * Every threshold of the camera path in one place (ROADMAP 1.3.0 risk mitigation): page
 * detection, auto-capture, and the capture-time quality report. Values are tuned on the
 * synthetic JVM scene set (docs/features-2.0/camera.md) and adjusted after the owner's phone check.
 *
 * [LEGACY_1_2] reproduces the 1.2.0 detector exactly, so the baseline stays measurable.
 */
data class DetectionConfig(
    // ---------------------------------------------------------------- detector: pre-processing
    /** Radius of the grey-level closing that removes thin dark text inside the page (0 = off). */
    val closeRadius: Int = 2,
    /** Percentile contrast stretch (CAM-01/02) when the p2..p98 spread is below this (0 = off). */
    val stretchBelowSpread: Int = 160,
    val stretchLowPct: Double = 0.02,
    val stretchHighPct: Double = 0.98,
    /** Upper bound of the stretch gain, so pure noise is not blown up. */
    val maxStretchGain: Float = 12f,
    // ---------------------------------------------------------------- detector: Canny
    val highPercentile: Double = 0.80,
    val strongFraction: Float = 0.3f,
    val highFloor: Int = 60,
    val highCeil: Int = 400,
    val passFactors: List<Float> = listOf(1f, 1.7f, 2.8f),
    // ---------------------------------------------------------------- detector: candidates
    val minArea: Float = 0.12f,
    val maxArea: Float = 0.995f,
    val minAngle: Float = 35f,
    val maxAngle: Float = 145f,
    val minSideSupport: Float = 0.45f,
    val minMeanSupport: Float = 0.62f,
    /**
     * The page differs from its surroundings with the same sign all round: the minimum share of
     * points per side whose inside/outside difference has the page's sign (0 = off, 1.2.0).
     */
    val minSideContrast: Float = 0.55f,
    /** Inside/outside difference (grey levels, after the stretch) that counts as contrast. */
    val sideContrastLevel: Int = 6,
    /** Score both channels and keep the best outline (true) or stop at the first confident one (1.2.0). */
    val fuseChannels: Boolean = true,
    /** Canny passes that also try quads from straight lines (0 = off, 1.2.0). */
    val linePasses: Int = 1,
    /** A line needs this many votes, as a fraction of the shorter frame side. */
    val lineMinVotes: Float = 0.12f,
    val maxLines: Int = 14,
    val maxLineQuads: Int = 400,
    /** Accept outlines cut by the frame border, flagged [Detection.cut] (CAM-04). */
    val detectCut: Boolean = true,
    // ---------------------------------------------------------------- confidence
    val confidentMinSupport: Float = 0.6f,
    val confidentMeanSupport: Float = 0.78f,
    // ---------------------------------------------------------------- auto-capture
    val holdMillis: Long = 900,
    val maxShift: Float = 0.02f,
    val graceMisses: Int = 4,
    val autoMinArea: Float = 0.12f,
    val rearmShift: Float = 0.15f,
    /** Frames in the temporal median of the corners (CAM-01/02). */
    val medianFrames: Int = 5,
    /** Corner speed above which the phone counts as moving (frame fraction per 100 ms, CAM-03). */
    val maxMotionPer100ms: Float = 0.03f,
    // ---------------------------------------------------------------- quality (CAM-03/04, PRD §4.3)
    /** Contrast-normalised Laplacian variance below which the live frame is blurry (320 px). */
    val liveBlurThreshold: Float = 0.010f,
    /** Same measure on the 960 px capture (the report). */
    val stillBlurThreshold: Float = 0.010f,
    /** Below this page contrast (p95 - p5) the page is blank: sharpness cannot be judged. */
    val minTextContrast: Int = 40,
    /** A corner within this fraction of the frame border means the page is cut (CAM-04). */
    val borderMargin: Float = 0.02f,
    /** A page smaller than this share of the frame is incomplete or too far (CAM-04). */
    val minCompleteArea: Float = 0.20f,
    /** Share of clipped highlights inside the page above which glare is reported (A4). */
    val glareShare: Float = 0.015f,
    val clipLevel: Int = 250,
    /** Mean page luminance below which the light is dim; noise sigma above which it is too. */
    val dimMean: Int = 95,
    val noisySigma: Float = 7f,
    val skewWarnDegrees: Float = 2f,
) {
    companion object {
        val DEFAULT = DetectionConfig()

        /** The 1.2.0 detector: no closing, no stretch, first confident channel wins, no side check. */
        val LEGACY_1_2 = DetectionConfig(
            closeRadius = 0, stretchBelowSpread = 0, minSideContrast = 0f, fuseChannels = false, detectCut = false, linePasses = 0,
            medianFrames = 1,
        )
    }
}
