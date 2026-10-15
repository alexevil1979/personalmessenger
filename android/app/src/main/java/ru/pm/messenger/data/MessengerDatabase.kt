package ru.pm.messenger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(entities = [ConversationEntity::class, MessageEntity::class], version = 4, exportSchema = false)
abstract class MessengerDatabase : RoomDatabase() {
    abstract fun chat(): ChatDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): MessengerDatabase =
        Room.databaseBuilder(context, MessengerDatabase::class.java, "messenger.db")
            .fallbackToDestructiveMigration(true)
            .build()

    @Provides
    fun chatDao(database: MessengerDatabase): ChatDao = database.chat()
}
