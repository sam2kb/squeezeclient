package de.maniac103.squeezeclient.cometd.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlaylistUrlsResponse(
    @SerialName("playlist_loop")
    val items: List<Item> = emptyList()
) {
    @Serializable
    data class Item(val url: String? = null)

    val urls: List<String> get() = items.mapNotNull { it.url }
}
