package de.maniac103.squeezeclient.cometd.request

import de.maniac103.squeezeclient.model.PlayerId

/**
 * Appends the stream or file behind [url] to the player's playlist. The server accepts exactly one
 * url per command.
 */
class AddPlaylistItemRequest(playerId: PlayerId, url: String) :
    NonPagedPlayerRequest(playerId, "playlist", "add", url)
