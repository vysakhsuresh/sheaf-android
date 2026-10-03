package com.layerbit.sheaf.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures whether the baseline profile actually makes Sheaf start faster.
 *
 * This exists because the question was asked and could not be answered. The profile is
 * generated, it reaches `assets/dexopt/baseline.prof` in the release APK, and
 * ProfileInstaller installs it without complaint - but `pm art dump` still reported
 * status=verify afterwards, and a cold start measured by hand took the same time with it and
 * without, 401ms against 435ms, which is noise.
 *
 * The honest reading of that is not "the profile does nothing". It is that the measurement
 * was wrong. `am force-stop` then `am start -W` leaves the page cache, the ART boot image and
 * the dex files hot, so it measures a warm start wearing a cold start's name; and a sideloaded
 * APK never takes the install-time path that Play uses to apply a profile. Two confounds, both
 * big enough to hide the effect.
 *
 * [MacrobenchmarkRule] removes them. [StartupMode.COLD] kills the process and drops what it
 * can between iterations, [iterations] gives a distribution rather than one number, and
 * [CompilationMode] is the variable: run the same journey under [CompilationMode.None] and
 * [CompilationMode.Partial] and the difference between the two medians IS the profile's worth,
 * on this device, in milliseconds.
 *
 * Run both and compare timeToInitialDisplayMs:
 *
 *     gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
 *         -P android.testInstrumentationRunnerArguments.class=\
 *            com.layerbit.sheaf.baselineprofile.StartupBenchmark
 *
 * Note this runs against the `benchmark` build type, not `nonMinified`: it has to measure the
 * app as it ships, R8 and all, or it measures something nobody will ever install.
 *
 * If the two come out the same, the profile is genuinely not helping and the honest move is to
 * say so in the README and reconsider whether the module earns its place. If Partial wins, the
 * number belongs in the README, because an unmeasured performance claim is just a hope.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupNoCompilation() = measure(CompilationMode.None())

    @Test
    fun startupWithBaselineProfile() = measure(CompilationMode.Partial())

    /**
     * The same journey the profile was generated from, so the two are measuring the same work.
     * Deliberately not a longer one: what is being compared is the cost of the cold path, and
     * every extra step after the first frame dilutes it with work the profile never covered.
     */
    private fun measure(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = APP_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.COLD,
        // Ten, not the five that is usual for a quick check. Cold start on a phone that is
        // also running everything else the owner installed is a noisy measurement, and the
        // difference being looked for here may be small.
        iterations = 10
    ) {
        pressHome()
        startActivityAndWait()
    }

    private companion object {
        /** `:app`'s applicationId. A test module cannot read the app's BuildConfig. */
        const val APP_PACKAGE = "com.layerbit.sheaf"
    }
}
