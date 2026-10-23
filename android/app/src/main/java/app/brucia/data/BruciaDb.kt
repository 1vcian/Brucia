package app.brucia.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ApEntity::class], version = 2, exportSchema = false)
abstract class BruciaDb : RoomDatabase() {
    abstract fun apDao(): ApDao

    companion object {
        @Volatile private var instance: BruciaDb? = null

        fun get(context: Context): BruciaDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, BruciaDb::class.java, "brucia.db"
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
