package io.github.stardomains3.oxproxion

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ChatSession::class, ChatMessage::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN modelUsed TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN cost REAL")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN provider TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN promptTokens INTEGER")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN completionTokens INTEGER")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN reasoningTokens INTEGER")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN durationMs INTEGER")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "chat_database"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
