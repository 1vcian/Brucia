package app.brucia.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migrations from the two pre-release schemas (see schemas/README.md for their source).
 *
 * v1 (85a74629): table `ap` without the position accumulators.
 * v2 (0c705937): `ap` gains wLat/wLon/wSum/seen.
 * v3 (7c541da4, every tagged release): adds table `saved`; `ap` unchanged.
 *
 * Neither v1 nor v2 was in a tagged release; they can exist only on devices that ran a
 * local debug build from 2026-10-23..27. The table SQL below is the Room-generated SQL of
 * schemas 2.json and 3.json, so a migrated database matches a fresh v3 one exactly.
 */
object BruciaMigrations {

    /** `ap` as created by Room for v2 and v3 (2.json / 3.json `createSql`). */
    private const val AP_V2_COLUMNS =
        "(`bssid` TEXT NOT NULL, `ssid` TEXT NOT NULL, `channel` INTEGER NOT NULL, " +
            "`rssi` INTEGER NOT NULL, `enc` TEXT NOT NULL, `lat` REAL, `lon` REAL, " +
            "`timeMillis` INTEGER NOT NULL, `wLat` REAL NOT NULL, `wLon` REAL NOT NULL, " +
            "`wSum` REAL NOT NULL, `seen` INTEGER NOT NULL, PRIMARY KEY(`bssid`))"

    /** `saved` as created by Room for v3 (3.json `createSql`). */
    private const val SAVED_V3 =
        "CREATE TABLE IF NOT EXISTS `saved` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`type` TEXT NOT NULL, `name` TEXT NOT NULL, `subtitle` TEXT NOT NULL, " +
            "`payload` TEXT NOT NULL, `timeMillis` INTEGER NOT NULL)"

    /**
     * Accumulators for one v1 row. A v1 row is a single sighting: rssi and lat/lon were
     * written together from the same report. This is what the v2 code stores for a first
     * sighting (w = 10^(rssi/10) with a fix, 0 without), so the old position keeps
     * contributing to the estimate after the next sighting.
     */
    data class Seed(val wLat: Double, val wLon: Double, val wSum: Double, val seen: Int)

    fun seedFromV1(rssi: Int, lat: Double?, lon: Double?): Seed {
        if (lat == null || lon == null) return Seed(0.0, 0.0, 0.0, 1)
        val w = Math.pow(10.0, rssi / 10.0)
        return Seed(lat * w, lon * w, w, 1)
    }

    /**
     * v1 -> v2: rebuild `ap` with the accumulator columns; every row is kept. The old table
     * is renamed first so the new one is created under its final name with Room's exact
     * SQL (a rename afterwards would rewrite the stored CREATE statement).
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `ap` RENAME TO `ap_v1`")
            db.execSQL("CREATE TABLE IF NOT EXISTS `ap` $AP_V2_COLUMNS")
            db.query(
                "SELECT `bssid`, `ssid`, `channel`, `rssi`, `enc`, `lat`, `lon`, `timeMillis` FROM `ap_v1`"
            ).use { c ->
                while (c.moveToNext()) {
                    val rssi = c.getInt(3)
                    val lat = if (c.isNull(5)) null else c.getDouble(5)
                    val lon = if (c.isNull(6)) null else c.getDouble(6)
                    val seed = seedFromV1(rssi, lat, lon)
                    val row = ContentValues().apply {
                        put("bssid", c.getString(0))
                        put("ssid", c.getString(1))
                        put("channel", c.getInt(2))
                        put("rssi", rssi)
                        put("enc", c.getString(4))
                        put("lat", lat)
                        put("lon", lon)
                        put("timeMillis", c.getLong(7))
                        put("wLat", seed.wLat)
                        put("wLon", seed.wLon)
                        put("wSum", seed.wSum)
                        put("seen", seed.seen)
                    }
                    db.insert("ap", SQLiteDatabase.CONFLICT_ABORT, row)
                }
            }
            db.execSQL("DROP TABLE `ap_v1`")
        }
    }

    /** v2 -> v3: add the generic `saved` table; `ap` is unchanged. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(SAVED_V3)
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
