package xyz.wraithnet.phantom.data

data class ServerProfile(
    val id: String,
    val name: String,
    val scheme: String,
    val address: String = "",
    val port: Int? = null,
    val raw: String,
    val countryFlag: String = "",
)

data class Subscription(
    val id: String,
    val title: String,
    val source: String,
    val servers: List<ServerProfile>,
    val updatedAt: Long = System.currentTimeMillis(),
    val updateIntervalSeconds: Long = 3600,
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    val expiresAt: Long? = null,
    val customTitle: String? = null,
    val customIntervalSeconds: Long? = null,
    val selectedServerId: String? = null,
    val announcement: String? = null,
    val supportUrl: String? = null,
    val webPageUrl: String? = null,
)

data class SubscriptionInfo(
    val title: String? = null,
    val updateIntervalSeconds: Long? = null,
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    val expiresAt: Long? = null,
)
