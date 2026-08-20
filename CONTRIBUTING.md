# Contributing to TempoBox

Thanks for taking a look! This is a personal project, but issues and PRs are
welcome.

## Getting set up

See the [README](README.md#building) for build requirements and IDE setup.
`CLAUDE.md` is the condensed map of the codebase's rules (module boundaries,
where settings/tag-IO/playlists live); `docs/ARCHITECTURE.md` explains the
design in depth. Reading those two first will save you time.

## Ground rules

- **Tests accompany behavior.** Logic changes come with unit tests; UI flows
  with Compose tests where practical. `./gradlew test testDebugUnitTest` must
  pass before a PR.
- **Dependency versions live in `gradle/libs.versions.toml`** — never inline a
  version in a build file.
- **Respect module boundaries** (CLAUDE.md "Hard rules"): UI → repositories
  only; tag writes through `core:tags`; settings reads/writes through
  `SettingsRepository`; playlists written as `.m3u8` only.
- **Style**: default Android Studio Kotlin style (`.editorconfig` is checked
  in), 4-space indent, ~120-column lines. Prefer small focused composables and
  pure, testable functions for logic.

## Pull requests

1. Fork/branch from `main`.
2. Make the change + tests; keep commits focused with imperative subjects.
3. Ensure CI passes (unit tests, lint, debug build run on every PR).
4. Describe *why* in the PR body; link an issue if one exists.

## Reporting bugs

Open an issue with device model, Android version, steps to reproduce, and —
for library/scan problems — the file format and a sample tag layout if
possible (never upload copyrighted audio).
