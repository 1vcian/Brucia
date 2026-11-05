package app.brucia.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room migrations from the pre-release v1/v2 schemas (schemas/1.json, 2.json, generated from
 * the commits that had them) to v3. Fixture rows are written with the historical SQL, then
 * migrated and validated by Room against the v3 schema, then read back.
 */
@RunWith(AndroidJUnit4::class)
class BruciaMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BruciaDb::class.java,
    )

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanUp() {
        context.deleteDatabase(DB)
    }

    @Test
    fun v1ToV2KeepsEveryRowAndSeedsAccumulators() {
        helper.createDatabase(DB, 1).use { insertV1Fixtures(it) }

        helper.runMigrationsAndValidate(DB, 2, true, BruciaMigrations.MIGRATION_1_2).use { db ->
            assertEquals(3, count(db, "ap"))
            val fix = readAp(db, GPS_BSSID)
            assertEquals("Cafe \"Wi-Fi\" ä", fix.ssid)
            assertEquals(6, fix.channel)
            assertEquals(-60, fix.rssi)
            assertEquals("WPA2", fix.enc)
            assertEquals(45.4642, fix.lat!!, 0.0)
            assertEquals(9.19, fix.lon!!, 0.0)
            assertEquals(1_729_690_000_000L, fix.timeMillis)
            val w = Math.pow(10.0, -60 / 10.0)
            assertEquals(45.4642 * w, fix.wLat, 0.0)
            assertEquals(9.19 * w, fix.wLon, 0.0)
            assertEquals(w, fix.wSum, 0.0)
            assertEquals(1, fix.seen)
            // The v2 estimate (wLat / wSum) reproduces the v1 position.
            assertEquals(45.4642, fix.wLat / fix.wSum, 1e-9)

            val noFix = readAp(db, NOFIX_BSSID)
            assertNull(noFix.lat)
            assertNull(noFix.lon)
            assertEquals(0.0, noFix.wLat, 0.0)
            assertEquals(0.0, noFix.wLon, 0.0)
            assertEquals(0.0, noFix.wSum, 0.0)
            assertEquals(1, noFix.seen)
            assertEquals("", noFix.ssid)
            assertEquals(-95, noFix.rssi)
        }
    }

    @Test
    fun v2ToV3KeepsApUnchangedAndCreatesSaved() {
        helper.createDatabase(DB, 2).use { db ->
            db.insert("ap", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("bssid", "aa:bb:cc:00:00:02"); put("ssid", "v2 net"); put("channel", 11)
                put("rssi", -48); put("enc", "OPEN"); put("lat", 41.9); put("lon", 12.5)
                put("timeMillis", 1_729_700_000_000L)
                put("wLat", 1.25); put("wLon", 0.5); put("wSum", 0.03); put("seen", 7)
            })
        }

        helper.runMigrationsAndValidate(DB, 3, true, BruciaMigrations.MIGRATION_2_3).use { db ->
            val ap = readAp(db, "aa:bb:cc:00:00:02")
            assertEquals(ApEntity("aa:bb:cc:00:00:02", "v2 net", 11, -48, "OPEN", 41.9, 12.5,
                1_729_700_000_000L, 1.25, 0.5, 0.03, 7), ap)
            assertEquals(0, count(db, "saved"))
            assertEquals(V3_SAVED_SQL, tableSql(db, "saved"))
        }
    }

    @Test
    fun v1ToV3ChainValidatesAndMatchesAFreshSchema() {
        helper.createDatabase(DB, 1).use { insertV1Fixtures(it) }
        helper.runMigrationsAndValidate(DB, 3, true, *BruciaMigrations.ALL).use { db ->
            assertEquals(3, count(db, "ap"))
            assertEquals(0, count(db, "saved"))
            // Same stored table SQL as a database Room creates from scratch at v3.
            assertEquals(V3_AP_SQL, tableSql(db, "ap"))
            assertEquals(V3_SAVED_SQL, tableSql(db, "saved"))
            db.query("SELECT COUNT(*) FROM sqlite_master WHERE name = 'ap_v1'").use {
                it.moveToFirst()
                assertEquals("temporary v1 table dropped", 0, it.getInt(0))
            }
        }
    }

    @Test
    fun appBuilderOpensAV1DatabaseAndKeepsItsRows() {
        helper.createDatabase(DB, 1).use { insertV1Fixtures(it) }
        openLikeTheApp().closing { room ->
            runBlocking {
                val aps = room.apDao().observeAll().first()
                assertEquals(listOf(GPS_BSSID, NOFIX_BSSID, QUOTE_BSSID), aps.map { it.bssid })
                val quoted = room.apDao().get(QUOTE_BSSID)!!
                assertEquals("it's; DROP TABLE ap", quoted.ssid)
                assertEquals(165, quoted.channel)
                assertEquals(-33.86, quoted.lat!!, 0.0)
                assertEquals(151.21, quoted.lon!!, 0.0)
                room.savedDao().insert(SavedItem(type = "nfc", name = "card", subtitle = "uid",
                    payload = "rfid read", timeMillis = 1L))
                assertEquals(1, room.savedDao().observe("nfc").first().size)
            }
            assertEquals(V3_IDENTITY_HASH, identityHash(room))
            assertEquals(V3_AP_SQL, tableSql(room.openHelper.readableDatabase, "ap"))
        }
    }

    @Test
    fun appBuilderOpensAV2DatabaseAndKeepsItsRows() {
        helper.createDatabase(DB, 2).use { db ->
            db.insert("ap", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("bssid", "aa:bb:cc:00:00:05"); put("ssid", "two"); put("channel", 36)
                put("rssi", -70); put("enc", "WPA2"); putNull("lat"); putNull("lon")
                put("timeMillis", 5L); put("wLat", 0.0); put("wLon", 0.0); put("wSum", 0.0)
                put("seen", 3)
            })
        }
        openLikeTheApp().closing { room ->
            val ap = runBlocking { room.apDao().get("aa:bb:cc:00:00:05") }
            assertEquals(ApEntity("aa:bb:cc:00:00:05", "two", 36, -70, "WPA2", null, null, 5L,
                0.0, 0.0, 0.0, 3), ap)
            assertEquals(V3_IDENTITY_HASH, identityHash(room))
        }
    }

    /** Same builder configuration as [BruciaDb.get], on the test database file. */
    private fun openLikeTheApp(): BruciaDb =
        Room.databaseBuilder(context, BruciaDb::class.java, DB)
            .addMigrations(*BruciaMigrations.ALL)
            .build()

    private fun <T> BruciaDb.closing(block: (BruciaDb) -> T): T =
        try { block(this) } finally { close() }

    private fun identityHash(room: BruciaDb): String =
        room.openHelper.readableDatabase
            .query("SELECT identity_hash FROM room_master_table WHERE id = 42")
            .use { it.moveToFirst(); it.getString(0) }

    private fun insertV1Fixtures(db: SupportSQLiteDatabase) {
        fun row(bssid: String, ssid: String, ch: Int, rssi: Int, enc: String,
                lat: Double?, lon: Double?, t: Long) = ContentValues().apply {
            put("bssid", bssid); put("ssid", ssid); put("channel", ch); put("rssi", rssi)
            put("enc", enc); put("lat", lat); put("lon", lon); put("timeMillis", t)
        }
        db.insert("ap", SQLiteDatabase.CONFLICT_ABORT,
            row(GPS_BSSID, "Cafe \"Wi-Fi\" ä", 6, -60, "WPA2", 45.4642, 9.19, 1_729_690_000_000L))
        db.insert("ap", SQLiteDatabase.CONFLICT_ABORT,
            row(NOFIX_BSSID, "", 1, -95, "WEP", null, null, 1_729_680_000_000L))
        db.insert("ap", SQLiteDatabase.CONFLICT_ABORT,
            row(QUOTE_BSSID, "it's; DROP TABLE ap", 165, -30, "WPA3", -33.86, 151.21, 1_729_670_000_000L))
    }

    private fun readAp(db: SupportSQLiteDatabase, bssid: String): ApEntity =
        db.query("SELECT bssid, ssid, channel, rssi, enc, lat, lon, timeMillis, wLat, wLon, wSum, seen " +
            "FROM ap WHERE bssid = ?", arrayOf(bssid)).use { c ->
            check(c.moveToFirst()) { "missing $bssid" }
            ApEntity(
                c.getString(0), c.getString(1), c.getInt(2), c.getInt(3), c.getString(4),
                if (c.isNull(5)) null else c.getDouble(5), if (c.isNull(6)) null else c.getDouble(6),
                c.getLong(7), c.getDouble(8), c.getDouble(9), c.getDouble(10), c.getInt(11),
            )
        }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) }

    private fun tableSql(db: SupportSQLiteDatabase, table: String): String =
        db.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table))
            .use { it.moveToFirst(); it.getString(0) }

    private companion object {
        const val DB = "migration-test.db"
        const val GPS_BSSID = "aa:bb:cc:00:00:01"
        const val NOFIX_BSSID = "aa:bb:cc:00:00:03"
        const val QUOTE_BSSID = "aa:bb:cc:00:00:04"
        const val V3_IDENTITY_HASH = "3fece0765235621512e631779fde69d8"
        // 3.json createSql with the table name substituted; SQLite stores the statement
        // without "IF NOT EXISTS" in sqlite_master.
        const val V3_AP_SQL = "CREATE TABLE `ap` (`bssid` TEXT NOT NULL, `ssid` TEXT NOT NULL, " +
            "`channel` INTEGER NOT NULL, `rssi` INTEGER NOT NULL, `enc` TEXT NOT NULL, `lat` REAL, " +
            "`lon` REAL, `timeMillis` INTEGER NOT NULL, `wLat` REAL NOT NULL, `wLon` REAL NOT NULL, " +
            "`wSum` REAL NOT NULL, `seen` INTEGER NOT NULL, PRIMARY KEY(`bssid`))"
        const val V3_SAVED_SQL = "CREATE TABLE `saved` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`type` TEXT NOT NULL, `name` TEXT NOT NULL, `subtitle` TEXT NOT NULL, " +
            "`payload` TEXT NOT NULL, `timeMillis` INTEGER NOT NULL)"
    }
}
