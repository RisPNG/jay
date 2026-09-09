package com.bnyro.clock.social.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.bnyro.clock.social.domain.DismissedSharedTimer
import com.bnyro.clock.social.domain.SharedAlarmLink
import com.bnyro.clock.social.domain.SocialGroup
import com.bnyro.clock.social.domain.SocialMember
import com.bnyro.clock.social.domain.PendingOperation
import com.bnyro.clock.social.domain.PendingSocialActivity
import com.bnyro.clock.social.domain.StagedResource
import com.bnyro.clock.social.domain.SyncClockState
import com.bnyro.clock.social.domain.SyncedResource
import com.bnyro.clock.social.domain.SyncScopeState

@Database(
    entities = [
        SocialGroup::class,
        SocialMember::class,
        SharedAlarmLink::class,
        DismissedSharedTimer::class,
        PendingOperation::class,
        PendingSocialActivity::class,
        StagedResource::class,
        SyncClockState::class,
        SyncedResource::class,
        SyncScopeState::class
    ],
    version = 1
)
abstract class SocialDatabase : RoomDatabase() {
    abstract fun socialDao(): SocialDao

    companion object {
        @Volatile
        private var instance: SocialDatabase? = null

        fun getDatabase(context: Context): SocialDatabase = instance ?: synchronized(this) {
            Room.databaseBuilder(context, SocialDatabase::class.java, "jay_social")
                .build()
                .also { instance = it }
        }
    }
}
