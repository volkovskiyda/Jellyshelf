package com.gmail.volkovskiyda.jellyshelf.data.remote

import com.gmail.volkovskiyda.jellyshelf.domain.BuildInfo
import org.junit.Test

/**
 * The one decision [RemoteUpdateFlags] makes for itself: a debug build never fetches.
 *
 * Provable on the JVM by the same trick [TesterSignInFallbackTest] uses for the SDK path: Firebase
 * is unreachable here — `FirebaseRemoteConfig.getInstance()` needs an initialized `FirebaseApp`,
 * which needs an Android runtime — so a `refresh()` that got past the gate would throw. A test
 * that completes at all is the proof.
 *
 * The gate matters beyond politeness: it is what keeps instrumented suites, Test Lab runs and the
 * debug variant off the network for this flag, the same contract Crashlytics and Performance
 * follow. `forceLegacySignIn()` is deliberately not covered the same way — it *should* read the
 * local store on any build, and the store is only reachable on a device.
 */
class RemoteUpdateFlagsTest {

    @Test
    fun aDebugBuild_neverTouchesRemoteConfig() {
        RemoteUpdateFlags(BuildInfo(isDebug = true, sdkInt = 36)).refresh()
    }
}
