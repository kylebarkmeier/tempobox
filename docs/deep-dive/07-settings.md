# Deep dive 7: Settings

> Prerequisites: [Android primer](../android-primer.md) §9 (DataStore), §6
> (StateFlow).

Settings look mundane, but this subsystem encodes several deliberate choices —
a typed snapshot model, a JSON-per-group storage layout, defensive decoding —
and it's the cleanest place to see the repo's end-to-end reactive pattern.
It's also governed by a hard rule (CLAUDE.md rule 5): **nothing outside
`core:settings` reads DataStore directly.**

## 1. The model: one immutable snapshot, grouped like the UI

[`AppSettings`](../../core/settings/src/main/kotlin/com/tempobox/settings/AppSettings.kt)
is a data class of nine groups, mirroring the Settings screen's sections:

```kotlin
data class AppSettings(
    val library: LibrarySettings = LibrarySettings(),      // locations, autoRescanAndWatch
    val ui: UiSettings = UiSettings(),                      // swipes, drawer items, card/list layouts
    val nowPlaying: NowPlayingSettings = NowPlayingSettings(), // corner actions, track info lines
    val queue: QueueSettings = QueueSettings(),             // persistQueue, confirmClear, allowDuplicates
    val bluetooth: BluetoothSettings = BluetoothSettings(), // startOnConnect, triple-taps, button remap
    val shuffle: ShuffleSettings = ShuffleSettings(),       // antiRepeat, ratingBias
    val scrobble: ScrobbleSettings = ScrobbleSettings(),    // broadcastScrobbles
    val artwork: ArtworkSettings = ArtworkSettings(),       // discogsToken, preferArtistImages
    val theme: ThemeConfig = ThemeConfig(),                 // colors, dark mode, Material You
)
```

Two things to notice:

- **The Kotlin defaults ARE the product defaults** — the class is the single
  place where "anti-repeat is ON by default" lives, and tests assert the spec
  against a fresh store (see §5). There is no second defaults table to drift.
- Values are real types, not strings: enums
  (`SwipeAction`, `VolumeTapAction`), maps
  (`Map<Corner, CornerAction>`, `Map<MediaButton, MediaButtonAction>`), and a
  *polymorphic sealed interface* (`DrawerItem`, which mixes four fixed
  objects with user-added `LibraryView(tab, label)` shortcuts). All are
  `@Serializable`.

## 2. Storage: JSON-per-group in Preferences DataStore

[`DataStoreSettingsRepository`](../../core/settings/src/main/kotlin/com/tempobox/settings/DataStoreSettingsRepository.kt)
stores each **group** as one JSON string under one preferences key
(`"library"`, `"ui"`, `"theme"`, …):

```kotlin
override val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
    AppSettings(
        library = prefs.decode(Keys.LIBRARY, LibrarySettings.serializer()) ?: LibrarySettings(),
        ui      = prefs.decode(Keys.UI, UiSettings.serializer()) ?: UiSettings(),
        ...
    )
}
```

Why this layout instead of the two obvious alternatives?

- *One key per primitive* (classic SharedPreferences style) can't hold the
  structured values at all without ad-hoc encoding, scatters a group's
  consistency across many keys, and makes adding a field a multi-site change.
- *One JSON blob for everything* makes every write re-serialize all settings
  and turns any decode failure into losing **all** settings.
- **Per-group JSON** is the middle: a write touches one group's key
  (read-modify-write of ~a hundred bytes), a corrupt value loses one group,
  and schema evolution is handled by kotlinx-serialization field defaults.

The serializer config is forward-compatible on purpose:
`ignoreUnknownKeys = true` (a downgrade reading a newer group's extra field
doesn't explode) and `encodeDefaults = true` (stored JSON is complete and
diffable when debugging).

**Defensive decoding** is the other half of the durability story:

```kotlin
private fun <T> Preferences.decode(key: Preferences.Key<String>, serializer: KSerializer<T>): T? =
    this[key]?.let { raw -> runCatching { json.decodeFromString(serializer, raw) }.getOrNull() }
```

A missing or unreadable group yields null → the caller substitutes defaults.
A malformed value (bit rot, an interrupted migration, a bug in an old
version) can never crash the app at startup — the worst case is one settings
group silently reset. The same `runCatching`-to-defaults posture appears in
`PlaybackStateStore` and `SmartRule.fromJson`; it's a house pattern:
**persisted bytes are input, and input is validated, not trusted.**

Writes go through one generic helper — decode current (or defaults), apply
the caller's transform, re-encode — inside `dataStore.edit { }`, which is
atomic and serialized per store, so concurrent `update*` calls compose instead
of clobbering (asserted by the `sequential transforms compose` test).

## 3. The interface and the hard rule

[`SettingsRepository`](../../core/settings/src/main/kotlin/com/tempobox/settings/SettingsRepository.kt)
is what the rest of the app sees: `settings: Flow<AppSettings>` plus one typed
updater per group, each taking a transform
(`updateShuffle { it.copy(ratingBias = true) }`). Transforms rather than
setters keep read-modify-write atomic at the API boundary and make partial
updates natural.

Consumers everywhere treat it uniformly:

- **UI**: ViewModels `stateIn` the flow and render from the snapshot
  ([`SettingsViewModel`](../../app/src/main/kotlin/com/tempobox/ui/settings/SettingsViewModel.kt),
  `MainViewModel` for theme/drawer).
- **Services**: `PlaybackService` keeps `settingsRepository.settings.stateIn(scope, Eagerly, null)`
  and consults `settingsState.value` inside broadcast receivers and session
  callbacks — those are synchronous call sites that can't suspend, and a
  stale-by-milliseconds read is fine there.
- **One-shot readers**: `settings.first()` where a single decision needs the
  current value (scanner startup, `addToQueue`'s duplicate policy, the
  scrobbler's enable check).

The DataStore *instance* itself is provided by
[`SettingsModule`](../../core/settings/src/main/kotlin/com/tempobox/settings/di/SettingsModule.kt)
via a Kotlin property delegate rather than a plain `@Singleton @Provides` —
the "one instance per process vs one per DI component" subtlety explained in
[deep dive 1 §2](01-startup-and-di.md).

## 4. Tracing one setting end-to-end

Adding a hypothetical "crossfade" toggle shows every layer a setting touches —
and how short the list is:

1. **Model + default**: add `val crossfade: Boolean = false` to the
   appropriate group in `AppSettings.kt`. Done — storage, decoding, and
   migration (old JSON without the field decodes to the default) all come for
   free from the serializer.
2. **Expose a writer**: nothing to add — the group's existing
   `updateNowPlaying { it.copy(crossfade = …) }`-style transform already
   covers it. (A brand-new *group* would add one key, one line in the
   `settings` map-block, and one `update*` function.)
3. **Settings UI**: a `SwitchRow` in the right section of
   [`SettingsSectionsMisc.kt`](../../app/src/main/kotlin/com/tempobox/ui/settings/SettingsSectionsMisc.kt)
   / [`SettingsSectionsLibraryUi.kt`](../../app/src/main/kotlin/com/tempobox/ui/settings/SettingsSectionsLibraryUi.kt),
   reading `settings.<group>.crossfade` and calling the updater on toggle.
4. **Consumption**: whoever implements the behavior collects
   `settingsRepository.settings` (or reads the service's `settingsState`) —
   it reacts live, because the DataStore flow re-emits on every write.

Compare the real example: `LibraryInitializer` restarts the folder watcher
when `autoRescanAndWatch` *or* the location list changes — that's just
`settings.map { … }.distinctUntilChanged().onEach { restart }`
([deep dive 2 §5](02-library-scanning-and-database.md)). No invalidation
callbacks, no "settings changed" broadcast: the flow is the notification.

## 5. Tests, and the lesson embedded in them

[`DataStoreSettingsRepositoryTest`](../../core/settings/src/test/kotlin/com/tempobox/settings/DataStoreSettingsRepositoryTest.kt)
(Robolectric + a real DataStore on a temp file) pins:

- fresh store ⇒ **the product defaults** (auto-rescan ON, anti-repeat ON,
  scrobble broadcasts ON, default swipes/drawer) — the spec encoded as
  assertions;
- group isolation (updating library doesn't disturb UI defaults);
- complex values (sealed `DrawerItem`s, enum-keyed maps, theme ARGBs)
  surviving the JSON round trip;
- sequential transforms composing.

One embedded lesson is worth quoting, because anyone writing a DataStore test
will hit it — the store cannot share the test's `TestScope`:

```kotlin
/**
 * DataStore keeps worker coroutines alive for the lifetime of the scope it
 * is given. Handing it the TestScope would make runTest fail with
 * UncompletedCoroutinesError (it waits for all children to finish), so the
 * store gets its own Job-rooted scope on the same test dispatcher —
 * cancelled in tearDown.
 */
private val storeScope = CoroutineScope(dispatcher + Job())
```

`runTest` asserts that no child coroutines outlive the test; DataStore's
internal actor deliberately outlives every call. Separate scope, same test
dispatcher (so execution stays deterministic), explicit cancel in `@After`.

And the module-level pin that applies to every Robolectric suite here:
`core/settings/src/test/resources/robolectric.properties` sets `sdk=35`
because Robolectric 4.14 tops out at SDK 35 while the project compiles
against SDK 37 ([deep dive 10](10-build-and-ci.md)).
