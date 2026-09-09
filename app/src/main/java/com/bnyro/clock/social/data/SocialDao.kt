package com.bnyro.clock.social.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bnyro.clock.social.domain.SharedAlarmLink
import com.bnyro.clock.social.domain.SocialGroup
import com.bnyro.clock.social.domain.SocialMember
import kotlinx.coroutines.flow.Flow
import com.bnyro.clock.social.domain.AlarmGroupName
import com.bnyro.clock.social.domain.DismissedSharedTimer
import com.bnyro.clock.social.domain.PendingOperation
import com.bnyro.clock.social.domain.PendingSocialActivity
import com.bnyro.clock.social.domain.StagedResource
import com.bnyro.clock.social.domain.SyncClockState
import com.bnyro.clock.social.domain.SyncedResource
import com.bnyro.clock.social.domain.SyncScopeState

@Dao
interface SocialDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueueActivity(activity: PendingSocialActivity)

    @Query("SELECT * FROM pending_social_activity")
    suspend fun getPendingActivity(): List<PendingSocialActivity>

    @Query("DELETE FROM pending_social_activity")
    suspend fun clearPendingActivity()

    @Query("SELECT * FROM sync_scopes WHERE route = :route")
    suspend fun getScope(route: String): SyncScopeState?

    @Query("SELECT * FROM sync_scopes")
    suspend fun getScopes(): List<SyncScopeState>

    @Query("DELETE FROM sync_scopes WHERE route = :route")
    suspend fun deleteScope(route: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putScope(scope: SyncScopeState)

    @Query("SELECT * FROM sync_resources")
    suspend fun getResources(): List<SyncedResource>

    @Query("SELECT * FROM sync_resources WHERE scopeId = :scopeId")
    suspend fun getScopeResources(scopeId: String): List<SyncedResource>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putResources(resources: List<SyncedResource>)

    @Query("DELETE FROM sync_resources WHERE scopeId = :scopeId AND kind = :kind AND `key` = :key")
    suspend fun deleteResource(scopeId: String, kind: String, key: String)

    @Query("DELETE FROM sync_resources WHERE scopeId = :scopeId")
    suspend fun clearScopeResources(scopeId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun stageResources(resources: List<StagedResource>)

    @Query("SELECT * FROM sync_staging WHERE scopeId = :scopeId")
    suspend fun getStagedResources(scopeId: String): List<StagedResource>

    @Query("DELETE FROM sync_staging WHERE scopeId = :scopeId")
    suspend fun clearStaging(scopeId: String)

    @Insert
    suspend fun enqueueOperation(operation: PendingOperation): Long

    @Query("SELECT * FROM pending_operations WHERE state = 'pending' ORDER BY sequence")
    suspend fun getPendingOperations(): List<PendingOperation>

    @Query("SELECT * FROM pending_operations WHERE state IN ('pending', 'accepted') ORDER BY sequence")
    suspend fun getProjectedOperations(): List<PendingOperation>

    @Query("SELECT * FROM pending_operations WHERE audioSourcePath IS NOT NULL ORDER BY sequence")
    suspend fun getAudioOperations(): List<PendingOperation>

    @Query("UPDATE pending_operations SET audioSourcePath = NULL WHERE operationId = :operationId")
    suspend fun completeAudioOperation(operationId: String)

    @Query("UPDATE pending_operations SET state = 'confirmed' WHERE state = 'accepted'")
    suspend fun confirmAcceptedOperations()

    @Query("SELECT * FROM pending_operations WHERE state = 'rejected' ORDER BY sequence DESC")
    fun getRejectedOperationsStream(): Flow<List<PendingOperation>>

    @Query("SELECT * FROM pending_operations WHERE kind = 'invitation' AND state IN ('accepted', 'confirmed') AND response IS NOT NULL ORDER BY sequence")
    fun getReadyInvitationsStream(): Flow<List<PendingOperation>>

    @Query("SELECT * FROM pending_operations WHERE operationId = :operationId")
    suspend fun getOperation(operationId: String): PendingOperation?

    @Query("UPDATE pending_operations SET state = :state, rejectionCode = :code, response = :response WHERE operationId = :operationId")
    suspend fun resolveOperation(operationId: String, state: String, code: String? = null, response: String? = null)

    @Query("SELECT * FROM sync_clock WHERE id = 1")
    suspend fun getClock(): SyncClockState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putClock(clock: SyncClockState)

    @Query("DELETE FROM sync_scopes")
    suspend fun clearScopes()

    @Query("DELETE FROM sync_resources")
    suspend fun clearResources()

    @Query("DELETE FROM sync_staging")
    suspend fun clearAllStaging()

    @Query("DELETE FROM pending_operations")
    suspend fun clearOperations()

    @Query("DELETE FROM sync_clock")
    suspend fun clearClock()

    @Query("SELECT * FROM social_groups ORDER BY name")
    fun getGroupsStream(): Flow<List<SocialGroup>>

    @Query("SELECT * FROM social_members ORDER BY groupId, role DESC, name")
    fun getMembersStream(): Flow<List<SocialMember>>

    @Query(
        "SELECT sal.localAlarmId, sal.remoteAlarmId, sal.groupId, sg.name AS groupName " +
                "FROM shared_alarm_links sal " +
                "JOIN social_groups sg ON sg.id = sal.groupId"
    )
    fun getAlarmGroupNamesStream(): Flow<List<AlarmGroupName>>

    @Query("SELECT * FROM shared_alarm_links WHERE localAlarmId = :localAlarmId")
    suspend fun getAlarmLinkByLocalId(localAlarmId: Long): SharedAlarmLink?

    @Query("SELECT * FROM shared_alarm_links WHERE remoteAlarmId = :remoteAlarmId")
    suspend fun getAlarmLinkByRemoteId(remoteAlarmId: String): SharedAlarmLink?

    @Query("SELECT * FROM shared_alarm_links")
    suspend fun getAlarmLinks(): List<SharedAlarmLink>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAlarmLink(link: SharedAlarmLink)

    @Query("DELETE FROM shared_alarm_links WHERE remoteAlarmId = :remoteAlarmId")
    suspend fun deleteAlarmLink(remoteAlarmId: String)

    @Query("DELETE FROM social_groups")
    suspend fun clearGroups()

    @Query("DELETE FROM social_members")
    suspend fun clearMembers()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putGroups(groups: List<SocialGroup>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMembers(members: List<SocialMember>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDismissedTimer(timer: DismissedSharedTimer)

    @Query("SELECT * FROM dismissed_shared_timers")
    suspend fun getDismissedTimers(): List<DismissedSharedTimer>

    @Query("DELETE FROM dismissed_shared_timers WHERE expiresAt < :before")
    suspend fun clearDismissedTimers(before: Long)

}
