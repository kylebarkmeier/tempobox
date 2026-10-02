# Android primer for backend/web developers

Everything in this document exists so the [deep dives](deep-dive/) can talk
about `PlaybackService`, Hilt, Compose, or Glance without stopping to explain
what those are. It covers only the Android/Jetpack concepts TempoBox actually
uses, each mapped onto a concept you already know from server or web work.

If you last touched Android when apps were XML layouts + `findViewById` +
`AsyncTask`: almost all of that is gone. Modern Android (this app) is Kotlin
coroutines + declarative UI + compile-time DI, and feels much closer to a
React/TypeScript + DI-container backend stack than to 2015 Android.

---

## 1. The app as a deployable: components & the manifest

An Android app is a sandboxed unit (an APK) that declares its entry points in
a manifest —
[`app/src/main/AndroidManifest.xml`](../app/src/main/AndroidManifest.xml).
Think of the manifest as a cross between a Kubernetes deployment spec and an
OpenAPI document: it tells the *operating system* what processes/entry points
exist, what they may be invoked with, and what capabilities (permissions) the
app needs. The OS — not your code — instantiates these components.

Four component types exist; TempoBox uses three:

| Component | Backend analogy | TempoBox instance |
|---|---|---|
| **Activity** | A single-page web app entry URL; the OS "routes" the user into it | [`MainActivity`](../app/src/main/kotlin/com/tempobox/MainActivity.kt) — the only activity; all screens are Compose destinations inside it |
| **Service** | A daemon/worker process the OS supervises | [`PlaybackService`](../core/playback/src/main/kotlin/com/tempobox/playback/PlaybackService.kt) — owns the audio player, keeps running when the UI is gone |
| **BroadcastReceiver** | A webhook/pub-sub subscriber | `TempoBoxWidgetReceiver` (widget updates), plus receivers registered in code for Bluetooth/volume events |
| ContentProvider | A queryable data API exposed to *other* apps | not used |

Two manifest details worth noticing:

- `MainActivity` has `launchMode="singleTask"` — roughly "singleton route":
  re-launching the app resumes the existing instance instead of stacking a new
  one.
- `PlaybackService` declares
  `foregroundServiceType="mediaPlayback"` and an intent filter for
  `androidx.media3.session.MediaSessionService`. The type is a *declared
  capability contract* — since Android 14 the OS refuses to start a foreground
  service whose type wasn't declared up front (think: a container that must
  declare its ports at build time).

**Intents** are the message envelopes between components — a typed,
OS-mediated event bus. `Intent(ACTION_VIEW, …)` from a file manager can open
TempoBox's playlist handler because the manifest declares an intent filter for
the M3U MIME types.

## 2. Process model & Binder IPC

Each app runs as its own Linux user in its own process(es); there is no shared
memory between apps. All cross-process communication goes through **Binder**,
a kernel-mediated RPC mechanism: you get a typed proxy object, call methods on
it, and arguments are serialized ("parceled") across the boundary — closer to
gRPC with generated stubs than to raw sockets.

Why you'll care even inside a single app: components like the playback service
are *designed* to be callable across processes, so their APIs behave like RPC
even when caller and callee happen to share a process. Concretely for
TempoBox:

- The UI talks to `PlaybackService` through a Media3 `MediaController`, which
  is a binder client. Objects crossing that boundary are **copied, not
  shared**, and some fields are deliberately stripped — e.g. Media3 removes a
  `MediaItem`'s file URI when it crosses the controller→session boundary
  (other apps' controllers shouldn't see your file paths), which is why
  `PlaybackService.onAddMediaItems` has to rebuild the URI from metadata
  extras. See [the playback deep dive](deep-dive/03-playback.md).
- Binder transactions have a small shared buffer (~1 MB per process). Chatty
  or bulky RPC can fail or stall the system — the root cause of the queue
  Bluetooth-flood incident covered in [the queue deep dive](deep-dive/04-queue.md).

**Process death is routine.** The OS kills app processes under memory pressure
without warning, like a pod being evicted. Well-behaved apps therefore
persist anything they'd hate to lose and restore it on next start — TempoBox
persists the play queue ([`PlaybackStateStore`](../core/playback/src/main/kotlin/com/tempobox/playback/PlaybackStateStore.kt))
for exactly this reason.

## 3. Foreground services & notifications

A plain background service can be killed or frozen aggressively. A
**foreground service** is a contract with the OS: "I'm doing something the
user actively cares about — keep me alive — and in exchange I will show a
persistent notification so the user knows I'm running." Analogy: a daemon
with a liveness endpoint the *user* can see; the notification is mandatory,
not courtesy UX.

For media apps the notification is doubly load-bearing: it *is* the playback
controls (play/pause/next with album art) on the lockscreen and in the shade.
Media3's `MediaSessionService` (which `PlaybackService` extends) manages the
foreground lifecycle and builds that notification automatically from the
session state — TempoBox never constructs the notification by hand; it feeds
the session good metadata and artwork and the system renders it.

Related manifest permissions: `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MEDIA_PLAYBACK` (the typed variant), `WAKE_LOCK` (keep the
CPU awake while decoding audio with the screen off).

## 4. Permissions

Android permissions come in tiers:

- **Install-time** (manifest-only): e.g. `INTERNET`. Granted silently.
- **Runtime** (dialog): the user approves at first use. TempoBox requests
  these on first launch in `MainActivity`:
  `READ_MEDIA_AUDIO` (API 33+; the modern scoped replacement for
  `READ_EXTERNAL_STORAGE`), `POST_NOTIFICATIONS` (API 33+ made showing
  notifications itself opt-in — without it the media notification is
  suppressed), and `BLUETOOTH_CONNECT` (API 31+, needed to observe device
  connections for start-on-connect).
- **Special access** (a dedicated Settings screen, not a dialog):
  `MANAGE_EXTERNAL_STORAGE`, a.k.a. **All files access**. Since Android 11,
  scoped storage means an app can *read* media via the media APIs but cannot
  *modify arbitrary files* it didn't create. A tag editor needs to rewrite
  audio files wherever the user keeps them, so TempoBox requests this — but
  only contextually, when a write first fails, from Settings ▸ Library.

The single source of truth for "what do we need and do we have it" is
[`LibraryPermissions`](../core/library/src/main/kotlin/com/tempobox/library/LibraryPermissions.kt):
`runtimePermissions()` builds the list per OS version, `hasAllFilesAccess()`
checks `Environment.isExternalStorageManager()`, and
`allFilesAccessIntent()` deep-links to the system grant screen. API-level
branching like this is normal Android: one binary runs on 8 OS versions
(minSdk 26 → Android 8.0), so version checks are the equivalent of feature
detection in browsers.

## 5. Jetpack Compose: React for Android

Compose is a declarative UI toolkit with the same core model as React:

| React | Compose |
|---|---|
| Function component returning JSX | `@Composable` function emitting UI |
| Re-render on state change | **Recomposition** on state change |
| `useState` | `remember { mutableStateOf(…) }` |
| `useEffect` | `LaunchedEffect(keys) { … }` |
| Context | `CompositionLocal` (e.g. `LocalSnackbar` in [`AppRoot.kt`](../app/src/main/kotlin/com/tempobox/ui/AppRoot.kt)) |
| Subscribing to a store | `flow.collectAsState()` |

Key mental shift from old Android: there are no XML layouts and no mutable
view objects to find and poke. UI = f(state). When `collectAsState()` sees a
new value in a `StateFlow`, exactly the composables reading that value
re-execute.

Material 3 is the design-system component library (buttons, dialogs,
navigation drawer); theming is covered in
[the UI deep dive](deep-dive/08-ui-architecture.md).

## 6. ViewModel + StateFlow: the state layer

A **ViewModel** is a presenter/store object scoped to a screen (or the
activity) that *survives configuration changes* — when the user rotates the
device, Android destroys and recreates the activity (historically: to reload
resources), but the ViewModel instance persists. Think of it as a per-route
singleton store, like a Redux slice bound to a route, with a built-in
`viewModelScope` (a coroutine scope cancelled when the screen goes away —
automatic request/subscription cleanup).

**Kotlin coroutines & Flow**, for orientation:

- `suspend fun` ≈ `async function` — non-blocking, sequential-looking.
- `Flow<T>` ≈ an async iterable / RxJS observable (cold by default).
- `StateFlow<T>` ≈ a BehaviorSubject: hot, always holds a current value,
  conflates (slow collectors skip intermediate values and see the latest).
- `SharedFlow<T>` ≈ an event bus for one-shot events (snackbar messages,
  navigation requests) where "current value" makes no sense.

The repo-wide pattern (see e.g.
[`LibraryViewModel`](../app/src/main/kotlin/com/tempobox/ui/library/LibraryViewModel.kt)):
repositories expose cold `Flow`s derived from the database; ViewModels
`stateIn(viewModelScope, …)` them into hot `StateFlow`s; composables
`collectAsState()` those. Data changes anywhere → the DB emits → every
screen showing it updates. There is no manual cache invalidation.

## 7. Room: the ORM over SQLite

Room is a compile-time ORM over the on-device SQLite database:

- `@Entity` data classes = table schemas
  ([`TrackEntity`](../core/database/src/main/kotlin/com/tempobox/database/entity/TrackEntity.kt)).
- `@Dao` interfaces = typed query repositories; you write real SQL in
  `@Query` annotations and Room **validates it against the schema at compile
  time** and generates the implementation
  ([`TrackDao`](../core/database/src/main/kotlin/com/tempobox/database/dao/TrackDao.kt)).
- A `@Query` returning `Flow<List<T>>` is a **live query**: Room tracks which
  tables a query reads and re-emits whenever those tables change. This is the
  engine behind "edit a tag and every screen updates" — closer to a
  subscription in Hasura/Firestore than to a classic ORM.
- `@Transaction` methods compose multiple DAO calls atomically.

Migrations are explicit and versioned, like Flyway. TempoBox is still at
schema v1 with `fallbackToDestructiveMigration()` (wipe-and-rescan on schema
change — acceptable because the DB is a rebuildable index of files on disk;
see [`DatabaseModule`](../core/database/src/main/kotlin/com/tempobox/database/di/DatabaseModule.kt)).

## 8. Hilt: the compile-time DI container

Hilt (built on Dagger) is a dependency-injection container resolved **at
compile time** — like a Spring/NestJS container, except the object graph is
generated and type-checked during the build; a missing binding is a build
error, not a runtime 500.

The vocabulary you'll see in the code:

- `@HiltAndroidApp` on
  [`TempoBoxApplication`](../app/src/main/kotlin/com/tempobox/TempoBoxApplication.kt)
  bootstraps the container for the process.
- `@Module` + `@InstallIn(SingletonComponent::class)` = a provider
  registration file for the process-wide scope. `@Provides` registers a
  factory function; `@Binds` registers an interface→implementation mapping.
- `@Inject constructor(…)` = constructor injection (the default and preferred
  form throughout `core:*`).
- `@AndroidEntryPoint` marks OS-instantiated classes (activities, services)
  for **field injection** — the OS calls their constructors, so Hilt injects
  `lateinit var` fields right after construction instead.
- `@HiltViewModel` wires ViewModels so `hiltViewModel()` inside a composable
  resolves them from the graph.
- `@EntryPoint` is the escape hatch for classes Hilt can't instrument at all
  (Glance widget callbacks) — a service-locator lookup into the same graph.

The full graph and ownership map is in
[deep dive #1](deep-dive/01-startup-and-di.md).

## 9. DataStore: typed key-value persistence

Preferences **DataStore** is the modern replacement for the old
`SharedPreferences`: an async (coroutine/Flow-based), transactional key-value
store backed by a file — think "a tiny Redis with a change feed", one instance
per file. Reads are a `Flow` that re-emits on every write; writes go through
atomic read-modify-write `edit { }` blocks.

TempoBox uses two independent stores: user settings
([`DataStoreSettingsRepository`](../core/settings/src/main/kotlin/com/tempobox/settings/DataStoreSettingsRepository.kt))
and playback state
([`PlaybackStateStore`](../core/playback/src/main/kotlin/com/tempobox/playback/PlaybackStateStore.kt)) —
deliberately separate files because one is *preferences* and the other is
*app state*. The JSON-per-group storage pattern and its rationale are in
[deep dive #7](deep-dive/07-settings.md).

One sharp edge worth knowing: a DataStore file may only have **one active
instance per process** (a second one throws). That constraint shapes how the
instances are created — see the comment in
[`SettingsModule`](../core/settings/src/main/kotlin/com/tempobox/settings/di/SettingsModule.kt).

## 10. Media3: ExoPlayer, MediaSession, and the Bluetooth bridge

Media3 is the Jetpack media stack. Three pieces matter here:

**ExoPlayer** — the actual audio engine: reads files, decodes (bundled
software decoders for MP3/FLAC/OGG; hardware/system `MediaCodec` for ALAC),
outputs PCM to the OS audio stack. It also models the *playlist*: its
**timeline** is an ordered list of media items with a current position.
TempoBox treats that timeline as the one and only play queue
([deep dive #4](deep-dive/04-queue.md)).

**MediaSession** — the interop hub. Think of it as a pub/sub topic the OS
subscribes to: the session publishes "what's playing + what controls exist",
and *consumers you don't control* render it — the lockscreen, the
notification shade, Android Auto, Wear OS, and Bluetooth devices. Commands
flow the other way: a headset button or car steering-wheel control arrives as
a session callback. Publish once, integrate with everything.

**The legacy bridge → AVRCP.** Bluetooth's metadata/control protocol (AVRCP —
what makes your car display show artist/title and its Next button work) is
served by the OS's Bluetooth stack, which speaks the *old*
`android.media.session.MediaSession` API. Media3 maintains a hidden legacy
session mirroring yours, and re-broadcasts state to all these "legacy
controllers" on every change. This bridge is invisible until it isn't: every
timeline change re-sends the **entire queue** through it, which is how a
naive per-item queue reorder flooded the Bluetooth stack
([deep dive #4](deep-dive/04-queue.md)).

The UI side never holds the player object. It connects a `MediaController`
(binder client) to the service and mirrors state through
[`PlayerConnection`](../core/playback/src/main/kotlin/com/tempobox/playback/PlayerConnection.kt) —
the full path is traced in [deep dive #3](deep-dive/03-playback.md).

## 11. Glance: the home-screen widget

Home-screen widgets don't run in your app's UI. They are **RemoteViews**: a
serialized UI description your app hands to the *launcher app*, which renders
it in its own process — conceptually server-side rendering where your app is
the server and the launcher is the browser. Consequences: a restricted widget
set, no arbitrary code in the widget, updates are pushed (re-render + re-send),
and there's a hard per-widget bitmap memory budget.

**Glance** lets you author that remote UI with Compose syntax
(`GlanceModifier`, `androidx.glance.layout.*` — similar names, different
imports, compiled down to RemoteViews). Interactions are declared as action
callbacks that run back in your app's process.
[`TempoBoxWidget`](../app/src/main/kotlin/com/tempobox/widget/TempoBoxWidget.kt)
is the whole pipeline; how it stays fresh (broadcast-driven re-render) and
maps player state to icons is in [deep dive #8](deep-dive/08-ui-architecture.md).

## 12. The build: Gradle, AGP, modules, convention plugins

Gradle is the build system (think: a typed, incremental Make/Bazel hybrid
configured in Kotlin). The **Android Gradle Plugin (AGP)** adds the
Android-specific machinery: compiling resources, merging manifests, producing
APKs, and the **debug/release build-type axis**.

TempoBox is a **multi-module build**: one `:app` application module and ten
`:core:*` library modules with dependencies pointing strictly downward (see
the graph in [ARCHITECTURE.md](ARCHITECTURE.md)). Benefits are the usual
monorepo-package ones: enforced layering, parallel/incremental compilation,
and — the one this repo leans on hardest — **`core:model` and `core:playlist`
are pure JVM modules**, so the most intricate logic unit-tests in
milliseconds without any Android runtime.

Two conventions keep 11 build files tiny:

- **Version catalog**
  ([`gradle/libs.versions.toml`](../gradle/libs.versions.toml)): every
  dependency and plugin version lives in one TOML file; build scripts
  reference typed accessors (`libs.androidx.media3.session`). Single source
  of truth, and Dependabot PRs touch one file.
- **Convention plugins**
  ([`build-logic/`](../build-logic/)): shared build configuration (SDK
  levels, JVM target, test deps) expressed as small Gradle plugins
  (`tempobox.android.library`, `tempobox.hilt`, …) that modules *apply*
  instead of copy-pasting config — like a shared ESLint/tsconfig preset, but
  for the whole build. Details in [deep dive #10](deep-dive/10-build-and-ci.md).

**Debug vs release**: the `debug` build type is auto-signed with a local
throwaway key, debuggable, and installs side-by-side as `com.tempobox.debug`
(an `applicationIdSuffix`). The `release` build is minified/shrunk (R8 —
think terser + tree-shaking for bytecode) and must be signed with the real
key for devices to accept it as an *update* to previous installs — Android
app identity is "applicationId + signing key", so losing the key means users
must uninstall to upgrade. CI signing with GitHub secrets is in
[deep dive #10](deep-dive/10-build-and-ci.md).

---

## Glossary cheat-sheet

| Android term | ≈ closest familiar concept |
|---|---|
| APK | signed, self-contained deployable (container image) |
| Manifest | deployment spec + capability declarations |
| Activity | SPA entry point / top-level route |
| (Foreground) Service | supervised daemon (with a user-visible liveness contract) |
| BroadcastReceiver / Intent | pub-sub subscriber / message envelope |
| Binder | kernel-mediated RPC with generated stubs |
| Process death | pod eviction; design for restart |
| Compose / recomposition | React / re-render |
| ViewModel | per-route store that survives route remounts |
| StateFlow / SharedFlow | BehaviorSubject / event bus |
| Room | compile-time-checked ORM + live queries over SQLite |
| Hilt | compile-time DI container |
| DataStore | tiny transactional KV store with a change feed |
| MediaSession | pub/sub hub the OS & Bluetooth subscribe to |
| RemoteViews / Glance | server-side-rendered UI hosted by the launcher |
| AGP build types | per-environment build profiles (debug/release) |
| R8 | minifier + tree-shaker for bytecode |
