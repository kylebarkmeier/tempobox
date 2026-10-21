package com.tempobox.remoteplay

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Browse side of a DLNA server (NAS, miniDLNA, Plex, Jellyfin...): the
 * ContentDirectory Browse action, paged, with DIDL-Lite parsing. Read-only;
 * playback of the returned HTTP resources happens on the local player.
 */
@Singleton
class ContentDirectoryClient @Inject constructor(private val upnp: UpnpControl) {

    /**
     * Children of [objectId] ("0" is the server root). Null when the request
     * itself failed; an empty list is a genuinely empty folder.
     */
    suspend fun browse(device: UpnpDevice, objectId: String): List<DidlLite.Entry>? {
        val controlUrl = device.contentDirectoryControlUrl ?: return null
        val entries = ArrayList<DidlLite.Entry>()
        var start = 0
        while (entries.size < MAX_ENTRIES) {
            val response = upnp.action(
                controlUrl,
                Soap.SERVICE_CONTENT_DIRECTORY,
                "Browse",
                listOf(
                    "ObjectID" to objectId,
                    "BrowseFlag" to "BrowseDirectChildren",
                    "Filter" to "*",
                    "StartingIndex" to start.toString(),
                    "RequestedCount" to PAGE_SIZE.toString(),
                    "SortCriteria" to "",
                ),
            )
            if (response == null) {
                // A failed later page still returns what we have; a failed
                // first page is a failed browse.
                if (start == 0) {
                    Log.w(TAG, "Browse '$objectId' on ${device.friendlyName} failed")
                    return null
                }
                Log.w(TAG, "Browse '$objectId' page at $start failed; returning partial listing")
                return entries
            }
            val returned = response["NumberReturned"]?.toIntOrNull() ?: 0
            entries += DidlLite.parseEntries(response["Result"].orEmpty())
            val total = response["TotalMatches"]?.toIntOrNull() ?: 0
            start += returned
            if (returned == 0 || (total in 1..start)) break
        }
        return entries
    }

    companion object {
        private const val TAG = "ContentDirectory"
        private const val PAGE_SIZE = 200

        /** Sanity cap: a pathological server cannot make the phone page forever. */
        private const val MAX_ENTRIES = 5_000
    }
}
