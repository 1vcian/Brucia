# Database schema history

Room exports schemas here during KSP compilation. Commit generated JSON when a schema
is first exported or changed. Destructive migration fallback stays removed: a database
version with no migration path must fail rather than delete research data.

| Version | Source commit | Contents | Shipped |
|---|---|---|---|
| 1 | `85a74629` (2026-10-23) | `ap` (bssid, ssid, channel, rssi, enc, lat, lon, timeMillis) | No |
| 2 | `0c705937` (2026-10-23) | `ap` + wLat, wLon, wSum, seen | No |
| 3 | `7c541da4` (2026-10-27) | v2 `ap` + `saved` | Every tagged release (v0.1 to v0.1.4) |

The three source commits are in the pre-0.1 development history, which was squashed
into the `v0.1` baseline (`096de5de`) and is no longer on any branch; the objects are
still in the local repository as unreferenced objects (`git show 85a74629`) and
can be pruned by `git gc`; the schema files here are the durable record. Versions 1 and 2 existed from
2026-10-23 15:26 to 2026-10-27 13:02, when the app had versionCode 1 and the first CI
job that built an APK (`83de6259`) did not exist yet. They can only be on a device that
ran a local debug build from that window.

`1.json` and `2.json` were generated, not written by hand: the `data` package and
`ApObservation.kt` of each source commit were built in a scratch Gradle project with
the current Room compiler (2.8.5), changing only `exportSchema = false` to `true`. The
same sources built with the toolchain of those commits (Gradle 8.11.1, AGP 8.7.3,
Kotlin 2.0.21, KSP 2.0.21-1.0.28, Room 2.6.1) give the same tables, columns, primary
keys and identity hashes (v1 `6d84a564...`, v2 `e39db8cd...`); only the JSON layout
differs (2.6.1 writes empty `indices`/`foreignKeys`/`views` and `"notNull": false`).
The same pipeline reproduces `3.json` from `7c541da4`: byte-identical to the committed
file with Room 2.6.1 apart from the final newline, semantically identical with 2.8.5.
`3.json` was originally recovered from the Room-generated `BruciaDb_Impl.java` and is
kept unchanged.

`BruciaMigrations` migrates 1 -> 2 -> 3 keeping every row; it is covered by
`BruciaMigrationTest` (instrumentation, MigrationTestHelper with these files) and
`BruciaMigrationsSeedTest` (JVM). The androidTest source set packages this directory
as assets.
