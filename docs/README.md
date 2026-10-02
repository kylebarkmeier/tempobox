# TempoBox documentation

Start here. This folder contains everything from the one-page decision record
to subsystem-by-subsystem walkthroughs of how TempoBox works under the hood.

The deep dives are written for an experienced engineer who is **not** an
Android developer: Android and Jetpack concepts are mapped onto general
backend/web equivalents (a foreground service ≈ a daemon with an OS contract,
Room ≈ an ORM over SQLite, Compose ≈ React-style declarative UI, and so on),
and each document explains *why* the design is the way it is, not just what
the code does.

## Reading order

1. **[Android primer](android-primer.md)** — the Android/Jetpack concepts this
   app is built on, explained for a backend/web developer. Read this first if
   your Android knowledge is small or outdated; everything else assumes it.
2. **[Architecture](ARCHITECTURE.md)** — the concise decision record: module
   graph and the key design decisions in one page.
3. **The deep dives** ([docs/deep-dive/](deep-dive/)) — one walkthrough per
   subsystem, tracing real code paths with file references. In rough
   dependency order:

   | # | Document | What it covers |
   |---|----------|----------------|
   | 1 | [App startup & the DI graph](deep-dive/01-startup-and-di.md) | `TempoBoxApplication`, Hilt modules, who owns what |
   | 2 | [Library scanning & the database](deep-dive/02-library-scanning-and-database.md) | Scanner diffing, `upsertKeepingUserData`, aggregates-as-queries, the file watcher |
   | 3 | [Playback end-to-end](deep-dive/03-playback.md) | Tap-a-track → `PlayerConnection` → binder → `PlaybackService`/ExoPlayer → audio; notification/lockscreen/Bluetooth; play counts & scrobbling |
   | 4 | [The queue](deep-dive/04-queue.md) | Timeline-as-queue, uids, `QueueReorder` batching (and the Bluetooth flood it prevents), persistence/restore |
   | 5 | [Shuffle engines](deep-dive/05-shuffle.md) | Plain / anti-repeat / rating-biased shuffle, and why ExoPlayer's shuffle isn't used |
   | 6 | [Playlists](deep-dive/06-playlists.md) | M3U8 codec, the smart-playlist rule engine, live evaluation, debounced exports |
   | 7 | [Settings](deep-dive/07-settings.md) | The DataStore JSON-per-group pattern, defensive decoding, how a new setting flows to the UI |
   | 8 | [UI architecture](deep-dive/08-ui-architecture.md) | Navigation, the single action layer, dialogs, theming, the Glance widget pipeline |
   | 9 | [Tags & editing](deep-dive/09-tags-and-editing.md) | jaudiotagger, the single-writer rule, prefill/dirty-field write sets, file-first-then-DB sync |
   | 10 | [Build & CI/release](deep-dive/10-build-and-ci.md) | Module graph, convention plugins, version catalog, CI jobs, release signing |

4. **[Releasing](RELEASING.md)** — the operational guide for cutting a release
   (versioning, tagging, APK signing secrets).

Also useful: [`CLAUDE.md`](../CLAUDE.md) at the repo root is the condensed
contributor guiderails (hard rules + gotchas) that both humans and AI agents
follow when changing the code.

## Conventions used in the deep dives

- File references are repo-relative links, e.g.
  [`core/playback/src/main/kotlin/com/tempobox/playback/PlayerConnection.kt`](../core/playback/src/main/kotlin/com/tempobox/playback/PlayerConnection.kt).
  Class and function names in the text match the source exactly.
- "≈" marks an analogy to a non-Android concept. Analogies are for intuition;
  where the mapping leaks, the text says so.
- Diagrams are Mermaid (rendered by GitHub) or ASCII.
