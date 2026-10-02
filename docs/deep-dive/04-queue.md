# Deep dive 4: The queue

> Prerequisites: [deep dive 3](03-playback.md) (PlayerConnection, MediaItems,
> the binder URI gotcha); [Android primer](../android-primer.md) §2 (binder),
> §10 (the legacy session bridge).

## 1. The design decision: the ExoPlayer timeline IS the queue

Most music players keep their own queue data structure and mirror it into the
player. TempoBox doesn't: **the ExoPlayer timeline (its ordered list of
`MediaItem`s) is the only queue there is.** Every queue feature — display,
reorder, remove, shuffle, persistence — is expressed as reads and mutations of
the timeline through
[`PlayerConnection`](../../core/playback/src/main/kotlin/com/tempobox/playback/PlayerConnection.kt).

Why: a parallel queue store has to be kept consistent with the player across
*every* mutation source — UI, widget, Bluetooth next/previous, media
notification, auto-advance at end of track, process restore. Each of those is
a chance for the two to drift ("the queue screen shows A but B is playing").
With the timeline as the single source of truth, drift is impossible by
construction; the cost is that mutations must be phrased as timeline
operations, which is exactly what the rest of this document is about.

### Queue identity: uids

Queue entries need identity ("remove *this* entry") distinct from track
identity, because the same track may be enqueued twice. The uid is a
monotonically increasing counter stored as the item's `mediaId`
([`MediaItems.toMediaItem`](../../core/playback/src/main/kotlin/com/tempobox/playback/MediaItems.kt)) —
`PlayerConnection.newItem(track)` assigns `nextUid++` per enqueue. The UI's
[`QueueItem`](../../core/model/src/main/kotlin/com/tempobox/model/PlayerModels.kt)
is just `(uid, Track)`.

Two maintenance details keep uids sound across restarts: `refresh()` re-seeds
`nextUid` above the max uid it sees in the timeline (a restored queue gets
uids `0..n-1` assigned by the service — see §4 — and the connection may
attach afterward), and the "original order" bookkeeping below is expressed in
uids, never indices, so it survives any reorder.

### Remembering the unshuffled order

Shuffle is a timeline reorder ([deep dive 5](05-shuffle.md)), so turning
shuffle *off* needs the pre-shuffle order remembered somewhere. That's
`originalOrderUids: List<Long>` in `PlayerConnection` — updated when a new
queue is set, extended on `addToQueue`, redefined after manual edits
(`playNext`, user reorders/removals: "a manual edit redefines the order"),
and filtered to surviving uids on un-shuffle. It's the only queue state that
lives outside the timeline, and losing it is benign (un-shuffle then just
keeps the current order).

## 2. The incident: why `QueueReorder` exists

The obvious implementation of "reorder the queue to a new order" is a loop of
`moveMediaItem(from, to)` — one move per track. That shipped, and toggling
shuffle on a 430-track queue **froze the app and could crash the phone's
Bluetooth stack**. The mechanism, worth understanding because it's invisible
in local testing without a car/headset connected:

1. Every timeline mutation makes Media3 rebuild the queue representation and
   publish it to the **platform media session**.
2. The platform session fans the *entire queue* out to every connected
   "legacy controller" — SystemUI, and crucially the **Bluetooth AVRCP
   service** (car head units and headsets browse the queue over AVRCP).
3. So *n* single-item moves serialize ~*n* full queues ≈ *n²* queue items
   through binder parcels. For n = 430 that's ≈ 185,000 parceled items, tens
   of megabytes — enough to OOM the Bluetooth process and to ANR the app
   inside `MediaSession.setQueue` (the fan-out happens synchronously on your
   thread).

The fix (PR #23) is to make any reorder cost **O(1) timeline operations**
regardless of queue length. That planning logic is
[`QueueReorder`](../../core/playback/src/main/kotlin/com/tempobox/playback/QueueReorder.kt) —
a pure object (no Android imports) whose KDoc carries the war story, with
`MAX_OPS = 4` as the hard budget. The lesson generalizes: **on Android, "the
framework will notify observers" can mean cross-process fan-out of your whole
data set; batch your mutations.** CLAUDE.md encodes it as a hard gotcha.

## 3. How a 4-op reorder works

`QueueReorder.plan(current, target, anchorUid)` plans reordering the uid list
`current` into `target` while keeping `anchorUid` — the currently *playing*
item — present in the timeline at every intermediate step, so audio never
hiccups. The executed plan
(`PlayerConnection.applyOrder`):

```
op 1  moveMediaItem(anchorIdx → 0)        (skipped if already at 0, or no anchor)
op 2  removeMediaItems(1, count)          (everything except the anchor, one op)
op 3  addMediaItems(tail)                 (all other items, in final order, one op)
op 4  moveMediaItem(0 → targetIdx)        (anchor to its final slot; skipped if 0)
```

Each op triggers one queue broadcast, so the parcel cost is ~4 queues ≈ O(n)
items instead of O(n²). The remove-then-re-add trick is safe precisely
because of the `onAddMediaItems` URI-rebuild hook from
[deep dive 3 §2](03-playback.md) — re-added items cross the binder without
their file URIs and get them restored service-side; `applyOrder`'s comment
calls this out.

Plan hygiene (mirrors what the old move-based code could and couldn't do):
uids in `target` that aren't in `current` are ignored; uids missing from
`target` are appended at the end in their current relative order; a plan that
would be a no-op returns null. When there's no valid anchor (nothing playing),
the plan is just remove-all + add-all. All of this is pinned by JVM unit
tests in
[`QueueReorderTest`](../../core/playback/src/test/kotlin/com/tempobox/playback/QueueReorderTest.kt).

### Bulk removal: the same concern, smaller hammer

Removing k selected queue entries one-by-one has the same quadratic smell, so
`QueueReorder.descendingRanges(indices)` collapses the removal set into
contiguous ranges ordered **back-to-front** (so earlier removals don't shift
later indices), and `PlayerConnection.removeQueueItems` issues one
`removeMediaItems(first, last+1)` per run. Worst case (alternating selection)
degrades gracefully to k ops; typical selections are a handful of runs.

## 4. Persistence & restore

The queue survives process death and reboots (Settings ▸ Queue ▸ "Restore
queue", default ON). Design: snapshot the *minimum reconstructable state* to
a dedicated small DataStore, deliberately separate from user settings —
[`PlaybackStateStore`](../../core/playback/src/main/kotlin/com/tempobox/playback/PlaybackStateStore.kt):

```kotlin
@Serializable
data class Snapshot(
    val trackIds: List<Long> = emptyList(),  // play order, by DB id
    val currentIndex: Int = 0,
    val positionMs: Long = 0,
    val repeatMode: RepeatMode = RepeatMode.OFF,
)
```

Shuffle *mode* is intentionally absent — the persisted order already reflects
whatever shuffle produced it, and re-marking the queue "shuffled" after a
reboot would imply an original order that no longer exists. (The snapshot is
JSON under one preferences key, `ignoreUnknownKeys`, `runCatching` decode →
null — the same defensive posture as settings, [deep dive 7](07-settings.md).)

**When snapshots happen**
([`PlaybackService`](../../core/playback/src/main/kotlin/com/tempobox/playback/PlaybackService.kt)):
debounced 1 s after track/play-pause/repeat events (`scheduleSnapshot`), every
15 s while playing (`startPeriodicSnapshots` — bounds position loss on a hard
kill), and a final `runBlocking` flush in `onDestroy`. Writes are one small
DataStore transaction, so the cadence is cheap.

**Restore** (`restoreQueue`, in service `onCreate`): load the snapshot, bail
if persistence is off / snapshot empty / the timeline already has items (a
controller won the race), fetch tracks via
`libraryRepository.getTracksByIds(snapshot.trackIds)` — which preserves the
id order and silently drops ids whose files left the library — rebuild
`MediaItem`s with fresh uids `0..n-1`, `setMediaItems(items, index, position)`,
restore repeat mode, and `prepare()` **with `playWhenReady` left false**:
the queue comes back ready at the right position, but a reboot never blasts
music unprompted.

## 5. The queue UI, briefly

[`QueueViewModel`](../../app/src/main/kotlin/com/tempobox/ui/queue/QueueViewModel.kt)
is a thin adapter over `PlayerConnection.queue`/`state` plus multi-select
state (a `Set<Long>` of uids; null = selection mode off). Tap plays that uid
(`playQueueItem` seeks by uid-resolved index); swipe-to-remove requires a
deliberate half-width drag (see the gesture note in
[`QueueScreen`](../../app/src/main/kotlin/com/tempobox/ui/queue/QueueScreen.kt))
so scroll flicks don't eat tracks; bulk actions wrap the selected tracks in a
`LibraryItem.TracksItem` and dispatch through the shared action layer like any
other library selection ([deep dive 8](08-ui-architecture.md)) —
`removeSelected()` funnels into the batched `removeQueueItems`.
`clearQueue` optionally confirms first (`queue.confirmClearQueue`, default ON).

Selection semantics are unit-tested on the JVM with a mocked
`PlayerConnection`
([`QueueViewModelTest`](../../app/src/test/kotlin/com/tempobox/ui/queue/QueueViewModelTest.kt)).
