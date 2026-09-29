package io.github.rmdodhia.gesturelauncher.home

import android.content.Context

/** Build without the Google Home APIs SDK (see README → Google Home setup). */
@Suppress("UNUSED_PARAMETER")
fun createHomeGateway(context: Context): HomeGateway = UnavailableHome(HOME_SDK_MISSING)
