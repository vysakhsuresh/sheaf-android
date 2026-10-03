package com.layerbit.sheaf.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writes the baseline profile that ships inside the release APK.
 *
 * ART interprets, then JITs, every method it has not been told about in advance. A Compose app
 * pays for that at the worst possible moment - the first screen - because the whole runtime,
 * composer and layout pass is cold. The profile is a list of the methods this run touched, and
 * ART compiles them at install time instead.
 *
 * Run it with `gradlew :app:generateBaselineProfile` against a connected device
 * running API 28 or newer, then commit what lands in `app/src/main/generated/baselineProfiles`.
 * It is not part of a release build: a build that cannot be made without a phone attached is a
 * build nobody can make.
 *
 * TWO THINGS TO KNOW BEFORE RUNNING IT, both found the hard way on a moto g84 on Android 15.
 *
 * The Gradle task fails even when the run succeeds. AGP's test-result collector reports
 * "Failed to receive the UTP test results" while the instrumentation itself passes and writes
 * a perfectly good profile, so the build goes red and the plugin never copies the file into
 * src/. When that happens the run is not wasted - drive it by hand and take the file:
 *
 *     adb install -r -t app/build/outputs/apk/nonMinifiedRelease/app-nonMinifiedRelease.apk
 *     adb install -r -t baselineprofile/build/outputs/apk/nonMinifiedRelease/baselineprofile-nonMinifiedRelease.apk
 *     adb shell am instrument -w com.layerbit.sheaf.baselineprofile/androidx.test.runner.AndroidJUnitRunner
 *     adb pull /sdcard/Android/media/com.layerbit.sheaf.baselineprofile/StartupProfileGenerator_generate-startup-prof.txt \
 *         app/src/main/generated/baselineProfiles/baseline-prof.txt
 *
 * And the profile that is checked in has NOT been shown to make this app start faster. It is
 * generated, it is packaged - assets/dexopt/baseline.prof is in the release APK - and
 * ProfileInstaller installs it without complaint, but `pm art dump` still reports
 * status=verify afterwards, and a force-stop cold start measures the same with it and
 * without. That may only mean the measurement is wrong: a force-stop leaves the page cache
 * warm, and a sideloaded APK does not take the install-time path that Play uses. Settling it
 * needs a macrobenchmark StartupBenchmark comparing CompilationMode.None with Partial, which
 * is the tool for the question and is not written yet.
 *
 * The journey stops at the first idle frame of the launcher screen. That covers process start,
 * the Application graph, the theme, navigation and the recents list, which is the expensive part
 * and the part every launch pays. Extending it through the viewer and a tool would cover more,
 * and is worth doing by someone who can watch the run and see that the selectors actually match.
 */
@RunWith(AndroidJUnit4::class)
class StartupProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = APP_PACKAGE,
        // Also writes the startup profile, which is what dex layout optimisation needs. Nothing
        // consumes it yet; collecting it now means turning that on later costs no device run.
        includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()
        // The first frame can beat the Room query that fills the recents list, and the classes
        // that query loads are startup classes whether or not the frame has already gone up.
        device.waitForIdle()
    }

    private companion object {
        /** `:app`'s applicationId. A test module cannot read the app's BuildConfig. */
        const val APP_PACKAGE = "com.layerbit.sheaf"
    }
}
