package com.tempobox.playback

/**
 * User-facing wording for queue and playlist actions, shared by the tap path
 * (LibraryActionsViewModel) and voice ([VoiceActions]) so a tapped and a
 * spoken "add to queue" produce the same toast. [detail] is what a spoken
 * request resolved to ("artist: Lamb of God"); the tap path passes none.
 */
object ActionMessages {

    fun countLabel(count: Int): String = if (count == 1) "1 track" else "$count tracks"

    /** [added] is the count really appended; [requested] what was asked for. */
    fun addedToQueue(added: Int, requested: Int, detail: String? = null): String = when {
        added == 0 -> "Already in queue" + suffix(detail)
        added < requested -> "Added ${countLabel(added)} to queue" +
            suffix(listOfNotNull(detail, "rest already queued").joinToString("; "))
        else -> "Added ${countLabel(added)} to queue" + suffix(detail)
    }

    /** The queue was replaced by [count] tracks; [detail] names what they are. */
    fun playing(count: Int, shuffled: Boolean, detail: String? = null): String =
        "Playing ${countLabel(count)}" + (if (shuffled) " shuffled" else "") + suffix(detail)

    fun playingNext(count: Int, detail: String? = null): String =
        "Playing ${countLabel(count)} next" + suffix(detail)

    fun addedToPlaylist(count: Int, playlist: String, detail: String? = null): String =
        "Added ${countLabel(count)} to \"$playlist\"" + suffix(detail)

    fun autoPlaylistRefusal(playlist: String): String =
        "Can't add tracks to \"$playlist\": its auto rule picks the tracks"

    fun brokenPlaylistRefusal(playlist: String): String =
        "Can't add tracks to \"$playlist\": its auto rule is broken"

    fun playlistGone(playlist: String): String =
        "Can't add tracks to \"$playlist\": it no longer exists"

    private fun suffix(detail: String?): String = if (detail.isNullOrEmpty()) "" else " ($detail)"
}
