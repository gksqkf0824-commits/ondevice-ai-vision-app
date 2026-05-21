package com.example.ondevice

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// 💡 entities에 User::class를 추가하고 버전을 6으로 올림
@Database(entities = [History::class, User::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun userDao(): UserDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "history_database"
                )
                    .fallbackToDestructiveMigration() // DB 구조 변경 시 앱이 튕기지 않게 기존 데이터 초기화
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
