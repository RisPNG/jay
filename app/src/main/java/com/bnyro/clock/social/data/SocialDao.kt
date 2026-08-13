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

@Dao
interface SocialDao {
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

}
