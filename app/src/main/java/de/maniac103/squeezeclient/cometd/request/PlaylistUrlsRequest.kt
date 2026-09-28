package de.maniac103.squeezeclient.cometd.request

import de.maniac103.squeezeclient.model.PagingParams
import de.maniac103.squeezeclient.model.PlayerId

/**
 * Requests the player's playlist including the url of every item. Neither is part of the menu
 * flavored status response the UI uses, but the urls are needed to restore the playlist of the
 * built-in player when the server dropped the player, and with it the playlist (see
 * SqueezeboxMediaPlayer).
 */
class PlaylistUrlsRequest(playerId: PlayerId) : Request(playerId, PagingParams.All, "status") {
    init {
        params["tags"] = "u"
    }
}
