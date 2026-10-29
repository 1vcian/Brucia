package app.brucia.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A generic saved capture (NFC card, RF signal, ...), persisted on the phone. */
@Entity(tableName = "saved")
data class SavedItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,     // "nfc" | "rf"
    val name: String,
    val subtitle: String,
    val payload: String,  // replay command or captured text
    val timeMillis: Long,
)

@Dao
interface SavedDao {
    @Query("SELECT * FROM saved WHERE type = :type ORDER BY timeMillis DESC")
    fun observe(type: String): Flow<List<SavedItem>>

    @Insert
    suspend fun insert(item: SavedItem)

    @Query("DELETE FROM saved WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM saved WHERE type = :type")
    suspend fun clear(type: String)
}
