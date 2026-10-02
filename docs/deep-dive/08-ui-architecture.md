# Deep dive 8: UI architecture

> Prerequisites: [Android primer](../android-primer.md) §5 (Compose), §6
> (ViewModel/StateFlow), §11 (Glance).

## 1. Shape of the UI: one activity, one shell, routed screens

TempoBox is a single-activity app:
[`MainActivity`](../../app/src/main/kotlin/com/tempobox/MainActivity.kt) does
edge-to-edge setup, first-launch permission requests, and
`setContent { AppRoot() }`; everything else is Compose.

[`AppRoot`](../../app/src/main/kotlin/com/tempobox/ui/AppRoot.kt) is the app
shell, layered like a typical SPA layout component:

- **Theme**: `TempoBoxTheme(config = settings.theme)`; the theme literally
  recomposes off the settings flow, so picking a new primary color re-skins
  the app live (§5).
- **Navigation drawer**: built from `settings.ui.drawerItems`, the four
  built-ins (Library, Now Playing, Queue, Settings) plus user-added
  `DrawerItem.LibraryView` shortcuts that deep-link to a specific library tab.
  The drawer is *data-driven UI configured by the user*: adding a shortcut
  is a settings write, not a code change.
- **Scaffold** with the shared snackbar host and the **mini player** as the
  `bottomBar`, shown on every route except full Now Playing whenever a track
  is loaded (`nowPlaying.track != null`), reading
  `MainViewModel.nowPlaying` (which is just `PlayerConnection.state`).
- **`TempoBoxNavHost`**: the route table.

Two `CompositionLocal`s (≈ React context) are provided here and matter later:
`LocalSnackbar` (any screen can toast feedback without prop-drilling) and
`LocalLibraryNavigator`, a function that executes the shared action layer's
"Go to artist/album" requests using the one `NavController` that lives in
`AppRoot` (§3).

### Navigation

[`Routes`](../../app/src/main/kotlin/com/tempobox/ui/navigation/Routes.kt) +
[`TempoBoxNavHost`](../../app/src/main/kotlin/com/tempobox/ui/navigation/TempoBoxNavHost.kt)
use Navigation-Compose: string route patterns with placeholders, like URL
templates:

```
library?tab={tab} · nowplaying · queue · settings · settings/{section}
artist/{name}?by={by} · album/{artist}/{album} · genre/{name} · playlist/{id}
```

The non-obvious details:

- **Dynamic segments are `Uri.encode`d** by the builder functions
  (`Routes.artist(name)` etc.), because artist/album/genre names come straight
  from tags and may contain `/`, spaces, emoji; raw interpolation would
  corrupt the route. (Playlists route by DB id instead: they have one;
  artists/albums don't, since they're GROUP BY projections,
  [deep dive 2](02-library-scanning-and-database.md).)
- The artist route carries `?by=album|track` because there are two artist
  paradigms (Album Artists vs track Artists), and the detail screen must
  query the right aggregation.
- Drawer navigation uses `launchSingleTop` + `popUpTo(library){saveState}` +
  `restoreState`: the standard recipe so drawer items behave like top-level
  tabs (no back-stack pile-up; tab state survives switching away and back).

Screens: `LibraryScreen` (the six-tab browser), detail screens
(artist/album/genre/playlist in
[`DetailScreens.kt`](../../app/src/main/kotlin/com/tempobox/ui/library/DetailScreens.kt)),
`NowPlayingScreen`, `QueueScreen`, and the settings pair. Per-screen state
lives in Hilt ViewModels
([`LibraryViewModel`](../../app/src/main/kotlin/com/tempobox/ui/library/LibraryViewModel.kt)
holds per-tab `SortSpec`s, genre filter chips, card/list layout toggles, all
feeding reactive repository queries).

Detail lists sort with the same `SortMenuButton` the tabs use. The option
list per view and the defaults live in `core:model`
([`LibrarySubview`](../../core/model/src/main/kotlin/com/tempobox/model/LibrarySubview.kt)):
album tracks default to disc/track number, artist and genre track lists to
album order, artist albums to release year, playlists to their stored order.
The chosen sorts are session state like the tab sorts, but detail ViewModels
die on back navigation, so they live in a singleton holder
([`SubviewSortState`](../../app/src/main/kotlin/com/tempobox/ui/library/SubviewSortState.kt))
keyed per view type. Playlist sorting is view-only: the detail ViewModel
applies `SortSpec.sortTracks` to the displayed list and never writes a sorted
order back through `PlaylistRepository`.

## 2. The single action layer

The core UI design decision: **every content action in the app is implemented
exactly once**, in
[`LibraryActionsViewModel`](../../app/src/main/kotlin/com/tempobox/ui/library/LibraryActionsViewModel.kt).
Play, shuffle, add-to-queue, play-next, add-to-playlist, create-auto-playlist,
go-to-artist/album, rate, edit-tags, remove-from-library, delete-from-device,
whether triggered from a list row tap, a 3-dot menu
([`LibraryItemMenu`](../../app/src/main/kotlin/com/tempobox/ui/components/LibraryItemMenu.kt)),
a configurable swipe, a Now Playing corner button, or a queue bulk selection.

The abstraction making that possible is
[`LibraryItem`](../../app/src/main/kotlin/com/tempobox/ui/library/LibraryItem.kt):
a sealed interface over *anything a list can show*: `TrackItem`, `AlbumItem`,
`ArtistItem(byAlbumArtist)`, `GenreItem`, `PlaylistItem`, and `TracksItem`
(an ad-hoc selection, e.g. from queue multi-select). The one operation that
unifies them:

```kotlin
suspend fun resolveTracks(item: LibraryItem): List<Track>   // in sensible play order
```

Every action is then "resolve to tracks, act" (`withTracks(item) { … }`), so
"shuffle a genre", "tag-edit an album", and "delete three selected queue
entries" are the same code path with different resolvers. The payoff is
uniformity as an *invariant*: behavior (and its tests) can't diverge between
views, and a new action is one function + one menu entry, available everywhere
at once.

Actions with consequences don't execute directly; they open a dialog first,
which is the second half of the layer:

### Dialogs: `ActionDialogHost`

The ViewModel exposes `dialog: StateFlow<Dialog?>` (a sealed type:
`ConfirmRemove`, `ConfirmDelete`, `AddToPlaylist`, `EditTags`, `Rate`,
`CreateAutoPlaylist`) plus two one-shot `SharedFlow`s: `messages` (snackbar
text) and `navigations` (go-to requests).
[`ActionDialogHost`](../../app/src/main/kotlin/com/tempobox/ui/components/ActionDialogHost.kt)
is a composable included **once per screen** that:

- renders whichever modal the state demands (confirmations with destructive
  styling, the playlist picker, the
  [tag editor](09-tags-and-editing.md), the rating dialog, the
  [smart-rule builder](06-playlists.md));
- collects `messages` into `LocalSnackbar`;
- collects `navigations` into `LocalLibraryNavigator`.

So a screen that wants the full standard behavior writes exactly two things:
menu/swipe callbacks that call `actions.*`, and one `ActionDialogHost(actions)`
line. State-driven dialogs (vs imperatively "showing" them) also mean a
rotation mid-confirmation keeps the dialog open for free; it's just state in
a ViewModel.

**"Go to artist/album"** (added with the standard-menu overhaul in PR #21)
shows why navigation is routed *through* the action layer rather than handled
per-screen: the mapping from an item to a destination has real rules. Tracks
open their *track* artist, falling back to the album-artist view only when the
artist tag is blank; albums open their album artist; multi-selections only
navigate when exactly one track is selected. That logic lives once, in
`LibraryActionsViewModel.artistDestination`/`albumDestination` (pure,
unit-testable companions), emits as a `Navigation` event, and `AppRoot`'s
navigator translates it to the same `Routes.artist`/`Routes.album` calls the
library screens use.

### Swipes

Swipe gestures are user-configurable (`UiSettings.swipeLeft/swipeRight` →
[`SwipeAction`](../../core/model/src/main/kotlin/com/tempobox/model/UiConfig.kt)),
and `performSwipe(action, item)` dispatches them into the same functions as
the menus. The gesture itself is wrapped in
[`DeliberateSwipe`](../../app/src/main/kotlin/com/tempobox/ui/components/DeliberateSwipe.kt)
/ [`SwipeableLibraryItem`](../../app/src/main/kotlin/com/tempobox/ui/components/SwipeableLibraryItem.kt),
which require a deliberate drag (not a scroll flick) before an action fires;
destructive-capable gestures earn friction.

## 3. Theming

[`TempoBoxTheme`](../../app/src/main/kotlin/com/tempobox/ui/theme/Theme.kt)
resolves a Material 3 `ColorScheme` from
[`ThemeConfig`](../../core/model/src/main/kotlin/com/tempobox/model/UiConfig.kt):

- dark mode: SYSTEM / LIGHT / DARK (SYSTEM follows `isSystemInDarkTheme()`);
- **Material You** (`useDynamicColor`, API 31+): the OS-derived
  wallpaper palette via `dynamicLight/DarkColorScheme`; when on, it
  overrides the custom colors;
- otherwise a scheme is **derived** from the user's three seed colors:
  on-colors picked by luminance (black text on light seeds, white on dark),
  containers mixed toward white (light) / black (dark), and dark-mode
  primaries lightened 25% so a saturated seed stays legible on dark surfaces.
  Deriving beats storing a full palette: any reasonable seed yields usable
  contrast, and the settings payload stays three ARGB longs.

The Settings swatch picker names the current preset in the row label and
checkmarks the selected swatch: maroon and green presets are the same hue
class under red-green colorblindness, so selection is never color-only.

## 4. The Glance widget pipeline

The widget is the furthest-flung consumer of UI state, and its pipeline is a
tour of remote-UI constraints
([primer §11](../android-primer.md)). Files:
[`TempoBoxWidget.kt`](../../app/src/main/kotlin/com/tempobox/widget/TempoBoxWidget.kt)
(widget + receiver + actions),
[`WidgetControls.kt`](../../app/src/main/kotlin/com/tempobox/widget/WidgetControls.kt)
(pure state→presentation mapping),
[`tempobox_widget_info.xml`](../../app/src/main/res/xml/tempobox_widget_info.xml)
(provider metadata: 4×2 default cells, resizable, `updatePeriodMillis=0`,
meaning no polling; updates are purely event-driven).

**Render path**: `provideGlance` grabs `PlayerConnection` and `TagReader`
through a Hilt `@EntryPoint` (Glance classes aren't injectable;
[deep dive 1 §2](01-startup-and-di.md)), then `provideContent { … }` composes
the remote UI. Two embedded lessons, both commented in the source:

- **Collect state *inside* the composition.** `player.state.collectAsState()`
  must happen inside `provideContent`; snapshotting before it would freeze the
  widget on whatever was playing at first render.
- **Cap the artwork bitmap.** `decodeScaledBitmap` downsamples embedded art
  to ≤ 512 px because RemoteViews enforces a per-widget bitmap memory budget;
  exceed it and the launcher **silently drops the update** ("the widget never
  updates" with no error anywhere).

A third, from the `ControlIcon` doc comment: the controls are tinted vector
drawables rather than text glyphs, because emoji glyphs render in fixed color,
ignore tinting, and made active/inactive state invisible.

**State mapping**: `WidgetControls` mirrors the in-app transport buttons so
the two surfaces can't disagree: shuffle/repeat render "active" for any
non-OFF mode, repeat ONE swaps in its own icon, play/pause picks its icon
from `isPlaying`, and content descriptions name the action a tap performs.
It's deliberately Glance-free so it unit-tests on the JVM; the shuffle/repeat
state icons arrived with PR #22. Active state is never tint-only: the widget
draws a tonal pill behind an ON toggle, and the in-app buttons swap to the
Material "on" glyphs (`TransportGlyphs`), because a primary-vs-gray tint
change is invisible to red-green colorblind users when the theme primary
lands in the green or red range.

**Refresh path**: Glance widgets re-render only when told. The chain is:
player event → `PlaybackService` (or, for shuffle-mode changes,
`PlayerConnection`, since shuffle is a timeline reorder, invisible as a player
event; [deep dive 3 §7](03-playback.md)) broadcasts the package-scoped
`com.tempobox.action.WIDGET_REFRESH` → `TempoBoxWidgetReceiver.onReceive`
calls `TempoBoxWidget().updateAll(context)` under `goAsync()` (the
BroadcastReceiver idiom for "I need a moment of async work before you may
freeze my process").

**Input path**: each button is an `actionRunCallback<T>()`; the `ActionCallback`
classes (`PlayPauseAction`, `NextAction`, `ShuffleAction`, …) resolve
`PlayerConnection` via the entry point and call the same command methods the
in-app buttons use. Tapping anywhere else launches `MainActivity`.

**Sizing**: `SizeMode.Exact` recomposes with the widget's real dimensions, and
art/control/text sizes are computed from `LocalSize`; the widget scales
continuously as the user resizes it instead of snapping between fixed layouts.

## 5. Now Playing specifics

[`NowPlayingScreen`](../../app/src/main/kotlin/com/tempobox/ui/nowplaying/NowPlayingScreen.kt)
is configuration-driven in two ways
([`NowPlayingSettings`](../../core/settings/src/main/kotlin/com/tempobox/settings/AppSettings.kt)):

- **Corner buttons**: the four corners around the artwork each bind a
  [`CornerAction`](../../core/model/src/main/kotlin/com/tempobox/model/UiConfig.kt)
  (go-to-artist/album, add-to-playlist, rate, edit-tags, set-as-wallpaper).
  `handleCornerAction` dispatches, again, to the shared action layer, except
  wallpaper, which is
  [`NowPlayingViewModel.setCurrentArtAsWallpaper`](../../app/src/main/kotlin/com/tempobox/ui/nowplaying/NowPlayingViewModel.kt)
  (reads the embedded art via `TagReader`, decodes, hands the bitmap to
  `WallpaperManager`).
- **Track info lines**: an ordered list of `TrackInfoField`s (artist, album,
  year, genre, format+sample-rate, bitrate, rating, play count) rendered
  under the title.

## 6. Testing the UI layer

- The action layer and ViewModels test on the JVM with mocked
  repositories/`PlayerConnection` (e.g.
  [`QueueViewModelTest`](../../app/src/test/kotlin/com/tempobox/ui/queue/QueueViewModelTest.kt)).
- End-to-end flows run as **instrumented Compose tests** with the real Hilt
  graph on a device/emulator:
  [`AppNavigationTest`](../../app/src/androidTest/kotlin/com/tempobox/AppNavigationTest.kt)
  (drawer, tabs) and
  [`SettingsFlowTest`](../../app/src/androidTest/kotlin/com/tempobox/SettingsFlowTest.kt)
  (settings sections, the double-confirmation reset), using
  [`HiltTestRunner`](../../app/src/androidTest/kotlin/com/tempobox/HiltTestRunner.kt)
  and `GrantPermissionRule` to pre-grant runtime permissions. CI runs these on
  an emulator for pushes to `main` ([deep dive 10](10-build-and-ci.md)).
