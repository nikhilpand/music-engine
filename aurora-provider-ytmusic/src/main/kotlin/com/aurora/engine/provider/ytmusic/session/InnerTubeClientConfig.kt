package com.aurora.engine.provider.ytmusic.session

import kotlinx.serialization.Serializable

@Serializable
data class InnerTubeClientConfig(
    val clientName: String,
    val clientVersion: String,
    val clientScreen: String? = null,
    val userAgent: String,
    val osName: String = "Android",
    val osVersion: String = "14",
    val platform: String = "MOBILE",
    val hl: String = "en",
    val gl: String = "US",
    val utcOffsetMinutes: Int = 0,
    val requiresCipher: Boolean = false,
    val requiresPoToken: Boolean = false,
    val supportsSabr: Boolean = false,
    val baseUrl: String? = null
) {
    companion object {
        val ANDROID_VR = InnerTubeClientConfig(
            clientName = "ANDROID_VR",
            clientVersion = "1.61.48",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Quest 3 Build/SQ3A.220605.009.A1) AppleWebKit/537.36 (KHTML, like Gecko) OculusBrowser/33.0.0.18.61.579483329 SamsungBrowser/4.0 Chrome/122.0.6261.139 Mobile VR Safari/537.36",
            osName = "Android",
            osVersion = "12",
            platform = "MOBILE",
            requiresCipher = false,
            requiresPoToken = false,
            supportsSabr = false,
            baseUrl = "https://www.youtube.com"
        )

        val ANDROID_MUSIC = InnerTubeClientConfig(
            clientName = "ANDROID_MUSIC",
            clientVersion = "6.42.52",
            userAgent = "com.google.android.apps.youtube.music/6.42.52 (Linux; U; Android 14; Pixel 8 Pro) gzip",
            osName = "Android",
            osVersion = "14",
            platform = "MOBILE",
            requiresCipher = false,
            requiresPoToken = false,
            supportsSabr = true
        )

        val WEB_REMIX = InnerTubeClientConfig(
            clientName = "WEB_REMIX",
            clientVersion = "1.20241030.01.00",
            clientScreen = "WATCH",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0",
            osName = "Windows",
            osVersion = "10.0",
            platform = "DESKTOP",
            requiresCipher = true,
            requiresPoToken = true,
            supportsSabr = false
        )

        val VISIONOS = InnerTubeClientConfig(
            clientName = "VISIONOS",
            clientVersion = "0.1",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15",
            osName = "visionOS",
            osVersion = "1.0",
            platform = "DESKTOP",
            requiresCipher = false,
            requiresPoToken = false,
            supportsSabr = false
        )

        val TVHTML5 = InnerTubeClientConfig(
            clientName = "TVHTML5",
            clientVersion = "7.20241030.12.00",
            userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/23.lts.4-devel (unlike Gecko) Starfish/2.2.0",
            osName = "Cobalt",
            osVersion = "23",
            platform = "TV",
            requiresCipher = false,
            requiresPoToken = false,
            supportsSabr = false
        )
    }
}
