# Changelog

## 1.2.4

Task Manager 1.2.4 focuses on conflict analysis, clearer attribution, and more reliable session capture.

### Added

- Pairwise lock attribution that records both the waiting thread and the lock owner thread when the JVM exposes that information
- Repeated conflict tracking across the rolling capture window so the mod can highlight recurring `waiter mod -> owner mod` contention instead of isolated one-off waits
- Broader thread role classification for worker pools, IO pools, chunk generation, chunk meshing, chunk upload, network, GC, render, and main logic threads
- Alternate owner candidates in thread drilldown so low-confidence ownership can be reviewed instead of being flattened into a single guess
- Dedicated conflict findings in the UI and HTML export, split into confirmed contention, repeated conflict candidates, weak heuristics, and unrelated slowdown causes

### Changed

- Scheduling-conflict detection no longer depends only on `Worker-Main-*` thread names and now uses thread names plus stack ancestry
- Conflict wording is more conservative and now distinguishes `Measured`, `Inferred`, `Pairwise inferred`, `Weak heuristic`, and reserved support for `Known incompatibility`
- Lock summaries prefer pairwise conflict phrasing when both sides of a wait can be identified
- Export summaries and diagnosis text now include top conflict candidates instead of only generic lock warnings
- Thread detail views now expose role labels, role-source labels, and alternate owner candidates

### Fixed

- `MANUAL_DEEP` recording now continues to capture session samples after recording starts, even if the Task Manager screen is closed
- Session exports no longer fall back to `No session samples were captured` for valid `MANUAL_DEEP` recordings started with `F11`
- Conflict and slowdown reporting no longer overstates certainty by calling inferred waits confirmed mod conflicts

### Notes

- `Known incompatibility` is currently a confidence tier and UI label, not a populated incompatibility database. In 1.2.4, conflict findings are still based on measured contention plus inferred ownership rather than a bundled registry of hardcoded bad mod pairs.
