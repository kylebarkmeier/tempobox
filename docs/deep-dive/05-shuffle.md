# Deep dive 5: Shuffle engines

> Prerequisites: [deep dive 4](04-queue.md) (timeline-as-queue, uids,
> `QueueReorder`).

## 1. Why not ExoPlayer's built-in shuffle?

ExoPlayer has `shuffleModeEnabled`: the timeline keeps its order and playback
follows a hidden random *traversal* of it. TempoBox never uses it, for three
reasons:

1. **The traversal isn't the product.** TempoBox ships three shuffle flavors —
   plain random, anti-repeat, rating-biased — and ExoPlayer's shuffle order is
   a uniform permutation you can't meaningfully customize.
2. **Two orders means two sources of truth.** With built-in shuffle, the queue
   screen either shows timeline order (not what will play) or has to reverse-
   engineer the traversal. With shuffle-as-reorder, the queue screen always
   shows exactly the play order — "what you see is what plays" needs no
   special cases anywhere.
3. **It composes with everything else.** Persistence stores the timeline
   order and automatically captures shuffle ([deep dive 4 §4](04-queue.md));
   `playNext`/manual reorders behave identically shuffled or not.

So: **shuffle is applied by physically reordering the timeline** with an order
computed by `ShuffleEngine`, batched through `QueueReorder` so it costs O(1)
timeline ops. Turning shuffle off restores the remembered pre-shuffle uid
order (`originalOrderUids`). Both paths keep the playing track first /
untouched so nothing audibly changes
([`PlayerConnection.setShuffleMode`](../../core/playback/src/main/kotlin/com/tempobox/playback/PlayerConnection.kt)).

One consequence to be aware of: since the timeline order *is* the shuffle,
`ShuffleMode` is app-level state, not player state — which is why the widget
needs a dedicated refresh nudge from `PlayerConnection` when it changes
([deep dive 3 §7](03-playback.md)).

## 2. The engine

[`ShuffleEngine`](../../core/playback/src/main/kotlin/com/tempobox/playback/ShuffleEngine.kt)
is a pure `@Singleton` with one entry point:

```kotlin
fun order(tracks: List<Track>, mode: ShuffleMode, random: Random = Random.Default): List<Int>
```

It returns a **permutation of indices** (not reordered tracks — callers map
indices back to their own item lists/uids), and takes an injectable `Random`
so every property below is tested deterministically with seeds. `OFF` returns
the natural order; `ALL` is a plain Fisher–Yates
(`indices.shuffled(random)`).

### Mode selection

The user doesn't pick a mode per-shuffle; Settings ▸ Shuffle has two toggles,
and `PlayerConnection.defaultShuffleMode(settings)` resolves what "shuffle on"
means: `ratingBias` → `RATING_BIASED`, else `antiRepeat` → `ANTI_REPEAT`
(the product default — `antiRepeat = true` in
[`ShuffleSettings`](../../core/settings/src/main/kotlin/com/tempobox/settings/AppSettings.kt)),
else `ALL`. The shuffle button cycles OFF ↔ that default.

## 3. `ANTI_REPEAT`: balanced shuffle

Problem with uniform shuffle on real libraries: with 6 tracks each from 3
artists, random order plays the same artist back-to-back ~5 times per run on
average, and users perceive that as "not random". Anti-repeat implements a
**balanced shuffle** (the engine's KDoc credits M. Fiedler's algorithm — the
classic "spread each group evenly" approach):

```kotlin
// keys, in priority order:
artist (effectiveAlbumArtist) → album (effectiveAlbum) → title
```

`balancedOrder` works recursively:

1. Group the items by the first key (artist).
2. Recursively balance each group by the remaining keys (so one artist's
   albums, and within an album its duplicate titles, are themselves spread).
3. Merge: each group's k-th element gets fractional position
   `(k + random) / groupSize`, and everything sorts by position.

Step 3 is the clever bit: a group of size *m* lands at positions ≈ 1/m, 2/m, …
of the full list — i.e. spread as uniformly as the pool allows — while the
per-element random jitter keeps runs different and interleaves groups
randomly. Groups of ≤ 2 items (and the recursion's base case) fall back to a
plain shuffle. Title is the last key so *identical tracks* (same song on an
album and a compilation) also end up far apart.

## 4. `RATING_BIASED`: weighted sampling without replacement

Goal: "play my favorites more often — but it's still my whole queue, and
don't cluster an artist." Implementation
(`ratingBiasedOrder`): repeatedly pick the next track from the remaining pool
with probability proportional to a weight, remove it, repeat.

The weight of a candidate:

```
w = 2^(rating − 3)          // 1★ 0.25 · 2★ 0.5 · 3★/unrated 1.0 · 4★ 2.0 · 5★ 4.0
w *= 0.25  if its artist appeared in the last 8 picks
w *= 0.5   if its album  appeared in the last 8 picks
```

Design notes:

- **Unrated = 3★.** The bias must not punish a library the user hasn't
  finished rating; neutral-by-default means rating a few favorites 5★ is
  enough to feel the effect (`ratingWeight(0) == 1.0` is pinned by a test).
- **Exponential curve.** 5★ is 16× more likely than 1★ at any given pick —
  strong enough to notice, but *sampling without replacement* guarantees every
  track still appears exactly once; low-rated tracks drift toward the end
  rather than disappearing.
- **The anti-repeat penalty is multiplicative and windowed** (last 8 picks,
  two `ArrayDeque`s) rather than reusing the balanced shuffle, because the
  two objectives — rating order bias and spacing — would fight if composed
  naively.
- `weightedPick` is a standard roulette-wheel selection with a uniform
  fallback when all weights are zero.

This is O(n²) in pool size (n picks × n weight evaluations), fine for queue
sizes (even 10k tracks is tens of millions of multiplications, once, on
`Default` dispatcher-adjacent call paths).

## 5. Testing statistical code without flakes

[`ShuffleEngineTest`](../../core/playback/src/test/kotlin/com/tempobox/playback/ShuffleEngineTest.kt)
is a nice case study in asserting randomized behavior deterministically:

- **Exact properties get exact asserts**: every mode returns a valid
  permutation; `OFF` is identity; fixed seed ⇒ fixed output; the weight curve
  values.
- **Statistical properties are averaged over many seeded runs with documented
  margins.** E.g. for 18 tracks / 3 artists, plain random averages ~5.3
  adjacent same-artist pairs; balanced shuffle simulates to ~1.7; the test
  averages 50 seeded runs and asserts < 3.5 — a ">5σ" margin the comment
  spells out. Rating bias is asserted as "summed positions of 5★ tracks <
  summed positions of 1★ tracks over 100 runs", not as any exact
  distribution.

Because the engine is pure JVM code with injected randomness, all of this
runs in milliseconds in the `core:playback` unit suite — no device, no
flakiness budget.
