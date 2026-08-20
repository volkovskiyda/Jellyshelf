package com.gmail.volkovskiyda.jellyshelf.ui

import com.gmail.volkovskiyda.jellyshelf.domain.ApkInstall
import com.gmail.volkovskiyda.jellyshelf.domain.model.InstallState
import com.gmail.volkovskiyda.jellyshelf.domain.model.UpdateInfo

/**
 * Never installs anything.
 *
 * The screens these checkers feed are about *offering* an update, not performing one — and a real
 * download started by a UI test would hit the network and outlive the test that started it.
 */
object InertApkInstall : ApkInstall {
    override suspend fun downloadAndInstall(
        info: UpdateInfo,
        onProgress: (InstallState.Running) -> Unit,
    ) = Unit
}
