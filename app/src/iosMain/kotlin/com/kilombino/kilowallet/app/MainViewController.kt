package com.kilombino.kilowallet.app

import androidx.compose.runtime.LaunchedEffect
import com.kilombino.kilowallet.app.i18n.I18n
import com.kilombino.kilowallet.app.i18n.phoneLanguages
import androidx.compose.ui.window.ComposeUIViewController
import com.kilombino.kilowallet.app.ui.KilobotOffers
import com.kilombino.kilowallet.app.ui.KilowalletTheme
import com.kilombino.kilowallet.app.ui.ScreenColumn
import com.kilombino.kilowallet.app.ui.App
import platform.UIKit.UIViewController

private var activeModel: WalletModel? = null

fun walletDidEnterBackground() { activeModel?.onBackground() }

/** Entry point for Swift: `MainViewControllerKt.MainViewController(bridge:demo:)`. */
fun MainViewController(bridge: IosBridge, demo: String?): UIViewController {
    val platform = IosPlatform(bridge, demo?.takeIf { it.isNotBlank() })
    val model = WalletModel(platform)
    activeModel = model
    return ComposeUIViewController { App(platform, model) }
}

/** Dedicated device-test root: memory-only platform stores; never initializes a wallet model. */
fun kilobotDevicePreview(bridge: IosBridge): UIViewController {
    val platform = IosPlatform(bridge, KILOBOT_DEVICE_QA)
    I18n.init(platform.prefs, phoneLanguages())
    return ComposeUIViewController {
        LaunchedEffect(I18n.lang.value) { I18n.ensure(I18n.lang.value) }
        KilowalletTheme { ScreenColumn { KilobotOffers(platform) } }
    }
}
