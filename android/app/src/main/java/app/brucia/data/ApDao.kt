package app.brucia.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ApDao {
    @Query("SELECT * FROM ap ORDER BY timeMillis DESC")
    fun observeAll(): Flow<List<ApEntity>>

    @Query("SELECT * FROM ap WHERE bssid = :bssid")
    suspend fun get(bssid: String): ApEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ap: ApEntity)

    @Query("DELETE FROM ap")
    suspend fun clear()
}
