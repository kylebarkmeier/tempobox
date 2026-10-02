# Security policy

## Supported versions

Only the latest release of TempoBox receives fixes. The app runs entirely on
your device, holds no accounts or server-side data, and talks to the network
only for optional Discogs artist images.

## Reporting a vulnerability

Please use GitHub's private vulnerability reporting:
**Security tab ▸ Report a vulnerability** on
<https://github.com/kylebarkmeier/tempobox/security>.

Do not open a public issue for security problems. You should get a response
within a week. If the report is accepted, a fix ships in the next release and
the advisory is published after it's available.

## Scope notes for researchers

- The app requests broad storage access (`MANAGE_EXTERNAL_STORAGE` /
  All-files-access) to scan and tag-edit the music library; reports about
  misuse of that access (path traversal via crafted tags/playlists, writing
  outside library folders) are very welcome.
- Playlist files (M3U/M3U8) and audio tags are untrusted input; parser issues
  are in scope.
- The release APK signing key is held only by the maintainer; CI signs via
  GitHub Actions secrets.
