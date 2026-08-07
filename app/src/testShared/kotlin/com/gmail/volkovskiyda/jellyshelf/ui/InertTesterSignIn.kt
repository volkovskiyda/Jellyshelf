package com.gmail.volkovskiyda.jellyshelf.ui

import com.gmail.volkovskiyda.jellyshelf.data.remote.TesterSignIn

/**
 * A sign-in launcher that reports it cannot run, which is what every test wants.
 *
 * `false` is the fall-back-to-the-SDK answer, and the SDK is unreachable off a device — so this
 * makes `AppDistributionSource.signInTester()` take a path the tests already override wholesale,
 * rather than one that would try to open a browser. Tests about the launcher's *own* decisions
 * declare their own stub.
 */
object InertTesterSignIn : TesterSignIn {
    override suspend fun start() = false
    override suspend fun awaitReturn() = Unit
}
