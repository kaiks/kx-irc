# AGENTS.md — kx-irc

## Project intent
Build a minimal Android IRC client (ZNC-friendly) that is reliable, readable, and practical for daily use. Keep the UI simple, stable, and functional over flashy design.

## Product expectations (current)
- Connection settings are a separate screen from chat.
- After Connect, show chat view automatically; Settings only via drawer entry.
- Top bar shows: `<current target> — <network>`.
- Drawer lists Channels, Private, Server, then Settings.
- Channel list sorted by most recently active.
- Message format: `HH:mm:ss (nick) message` (single line).
- Messages are selectable/copyable.
- ZNC playback support: handle server-time tags when present and strip `[HH:MM:SS]` in message body.

## Work style
- Prefer simple, explicit Kotlin/Compose code; avoid clever abstractions.
- Keep dependencies minimal and stable.
- When adding UI or behavior changes, also update tests.
- Run `./gradlew test assembleDebug` for changes; run `./gradlew connectedAndroidTest` when UI tests are updated.
- If a change introduces friction for the emulator (scrolling, IME, etc.), add test tags and stabilize tests.

## Debugging guidelines
- If message ordering/delivery looks wrong, capture raw lines to diagnose. Prefer copy-to-clipboard helpers over manual log digging.
- Avoid assuming IRCv3 features are always available; negotiate capabilities and degrade gracefully.

## Communication style
- Concise, actionable updates.
- Summarize changes and mention test results.
- Offer next steps if there are clear follow-ups.
