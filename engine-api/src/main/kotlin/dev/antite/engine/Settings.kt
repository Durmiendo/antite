package dev.antite.engine

enum class ColorScheme { SYSTEM, LIGHT, DARK }

enum class TrackingProtection { STANDARD, STRICT, OFF }

enum class CookiePolicy { ISOLATE_THIRD_PARTY, BLOCK_TRACKERS, BLOCK_THIRD_PARTY, ALLOW_ALL }

enum class FingerprintProtection { PRIVATE_ONLY, ALWAYS, MAXIMUM }

enum class WebRtcPolicy { DEFAULT, HIDE_LOCAL_IP, PROXY_ONLY, DISABLED }

enum class DnsOverHttpsMode { OFF, FALLBACK, ONLY }

enum class PermissionDefault { ASK, ALLOW, BLOCK }

data class EngineSettings(
    val colorScheme: ColorScheme = ColorScheme.DARK,
    val fontScale: Float = 1f,
    val forceUserZoom: Boolean = false,
    val doubleTapZoom: Boolean = true,
    val globalPrivacyControl: Boolean = true,
    val doNotTrack: Boolean = false,
    val httpsOnly: Boolean = false,
    val safeBrowsing: Boolean = false,
    val tracking: TrackingProtection = TrackingProtection.STANDARD,
    val cookies: CookiePolicy = CookiePolicy.ISOLATE_THIRD_PARTY,
    val stripTrackingParams: Boolean = true,
    val fingerprinting: FingerprintProtection = FingerprintProtection.PRIVATE_ONLY,
    val webRtc: WebRtcPolicy = WebRtcPolicy.HIDE_LOCAL_IP,
    val preload: Boolean = false,
    val dohMode: DnsOverHttpsMode = DnsOverHttpsMode.OFF,
    val dohUrl: String? = null,
    val locales: List<String> = emptyList(),
    val offerTranslation: Boolean = true,
    val motionSensors: Boolean = true,
    val permissionDefaults: Map<SitePermission, PermissionDefault> = emptyMap(),
    val savePasswords: Boolean = true,
    val autofillPasswords: Boolean = true,
    val autofillAddresses: Boolean = true,
    val autofillCards: Boolean = true,
)
