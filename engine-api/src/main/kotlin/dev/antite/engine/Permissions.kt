package dev.antite.engine

enum class SitePermission {
    LOCATION,
    NOTIFICATIONS,
    PERSISTENT_STORAGE,
    XR,
    AUTOPLAY_AUDIBLE,
    AUTOPLAY_INAUDIBLE,
    DRM,
    LOCAL_NETWORK,
    LOCAL_DEVICE,
    TRACKING_EXCEPTION,
    STORAGE_ACCESS,
}

enum class PermissionValue { ASK, ALLOW, BLOCK }

data class PermissionEntry(
    val origin: String,
    val permission: SitePermission,
    val value: PermissionValue,
    val privateMode: Boolean,
)

interface PermissionStore {
    fun list(done: (List<PermissionEntry>) -> Unit)

    fun set(origin: String, permission: SitePermission, value: PermissionValue)

    fun remove(entry: PermissionEntry)
}

class ContentPermissionRequest(
    val origin: String,
    val permission: SitePermission,
    val privateMode: Boolean,
    private val answer: (Boolean) -> Unit,
    private val forgetStored: () -> Unit,
) {
    fun allow() = answer(true)

    fun deny() = answer(false)

    fun forgetStoredAnswer() = forgetStored()
}

class MediaPermissionRequest(
    val origin: String,
    val camera: Boolean,
    val microphone: Boolean,
    private val grant: (camera: Boolean, microphone: Boolean) -> Unit,
    private val rejectAll: () -> Unit,
) {
    fun grant(camera: Boolean, microphone: Boolean) {
        if (!camera && !microphone) rejectAll() else grant.invoke(camera, microphone)
    }

    fun reject() = rejectAll()
}

class AndroidPermissionRequest(
    val permissions: List<String>,
    private val result: (Boolean) -> Unit,
) {
    fun granted() = result(true)

    fun denied() = result(false)
}
