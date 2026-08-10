package com.bnyro.clock.social.data

import android.content.Context
import android.content.Intent
import androidx.room.withTransaction
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.repository.AlarmRepository
import com.bnyro.clock.domain.usecase.CreateUpdateDeleteAlarmUseCase
import com.bnyro.clock.social.domain.AlarmActivityKind
import com.bnyro.clock.social.domain.AlarmActivityRequest
import com.bnyro.clock.social.domain.AlarmPermission
import com.bnyro.clock.social.domain.GroupCreate
import com.bnyro.clock.social.domain.GroupUpdate
import com.bnyro.clock.social.domain.MemberRole
import com.bnyro.clock.social.domain.SharedAlarmLink
import com.bnyro.clock.social.domain.SharedAlarmRequest
import com.bnyro.clock.social.domain.SocialActivity
import com.bnyro.clock.social.domain.SocialGroup
import com.bnyro.clock.social.domain.SocialMember
import com.bnyro.clock.social.domain.SharedAlarmDelivery
import com.bnyro.clock.util.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.net.URI
import java.time.Instant
import com.bnyro.clock.util.services.AlarmService
import com.bnyro.clock.util.AlarmHelper

data class SocialSyncResult(
    val newActivity: List<SocialActivity>,
    val deviceId: String
)

class SocialRepository(
    private val context: Context,
    private val socialDatabase: SocialDatabase,
    private val alarmRepository: AlarmRepository
) {
    private val socialDao = socialDatabase.socialDao()
    private val alarmUseCase = CreateUpdateDeleteAlarmUseCase(context, alarmRepository)

    val groups: Flow<List<SocialGroup>> = socialDao.getGroupsStream()
    val members: Flow<List<SocialMember>> = socialDao.getMembersStream()
    val activity: Flow<List<SocialActivity>> = socialDao.getActivityStream()
    val alarmGroupNames = socialDao.getAlarmGroupNamesStream()
    val alarmDeliveryCounts = socialDao.getAlarmDeliveryCountsStream()

    suspend fun synchronize(): SocialSyncResult = withContext(Dispatchers.IO) {
        val serverUrl = Preferences.instance.getString(
            Preferences.jayServerUrlKey,
            DEFAULT_SERVER_URL
        ) ?: DEFAULT_SERVER_URL
        val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
        val api = SocialApi(serverUrl, identity)
        api.register()
        val previousCursor = Preferences.instance.getLong(Preferences.jaySyncCursorKey, 0)
        val response = api.synchronize(previousCursor)
        val remoteGroupIds = response.groups.map { it.id }.toSet()
        val remoteAlarmIds = response.alarms.map { it.id }.toSet()
        val previousActivityIds = socialDao.getActivityIds().toSet()

        socialDao.getAlarmLinks().filter {
            it.groupId !in remoteGroupIds || it.remoteAlarmId !in remoteAlarmIds
        }.forEach { link ->
            alarmRepository.getAlarmById(link.localAlarmId)?.let {
                context.sendBroadcast(
                    Intent(AlarmService.CANCEL_SHARED_ALARM_INTENT_ACTION)
                        .setPackage(context.packageName)
                        .putExtra(AlarmHelper.EXTRA_ID, it.id)
                )
                alarmUseCase.deleteAlarm(it)
            }
            socialDao.deleteAlarmLink(link.remoteAlarmId)
        }

        response.alarms.forEach { remote ->
            val link = socialDao.getAlarmLinkByRemoteId(remote.id)
            if (remote.deleted) {
                link?.let {
                    alarmRepository.getAlarmById(it.localAlarmId)?.let { alarm ->
                        context.sendBroadcast(
                            Intent(AlarmService.CANCEL_SHARED_ALARM_INTENT_ACTION)
                                .setPackage(context.packageName)
                                .putExtra(AlarmHelper.EXTRA_ID, alarm.id)
                        )
                        alarmUseCase.deleteAlarm(alarm)
                    }
                    socialDao.deleteAlarmLink(remote.id)
                }
            } else if (link == null) {
                val localId = alarmUseCase.createAlarm(
                    Alarm(
                        time = remote.time,
                        label = remote.label,
                        enabled = remote.enabled,
                        days = remote.days,
                        vibrate = remote.vibrate,
                        repeat = remote.repeat,
                        snoozeEnabled = remote.snoozeEnabled,
                        snoozeMinutes = remote.snoozeMinutes,
                        soundEnabled = remote.soundEnabled,
                        vibrationPattern = remote.vibrationPattern,
                        vibrationPatternName = remote.vibrationPatternName
                    )
                )
                socialDao.putAlarmLink(
                    SharedAlarmLink(remote.id, localId, remote.groupId, remote.revision)
                )
            } else if (remote.revision > link.revision) {
                alarmRepository.getAlarmById(link.localAlarmId)?.let { local ->
                    context.sendBroadcast(
                        Intent(AlarmService.CANCEL_SHARED_ALARM_INTENT_ACTION)
                            .setPackage(context.packageName)
                            .putExtra(AlarmHelper.EXTRA_ID, local.id)
                    )
                    alarmUseCase.updateAlarm(
                        local.copy(
                            time = remote.time,
                            label = remote.label,
                            enabled = remote.enabled,
                            days = remote.days,
                            vibrate = remote.vibrate,
                            repeat = remote.repeat,
                            snoozeEnabled = remote.snoozeEnabled,
                            snoozeMinutes = remote.snoozeMinutes,
                            soundEnabled = remote.soundEnabled,
                            vibrationPattern = remote.vibrationPattern,
                            vibrationPatternName = remote.vibrationPatternName
                        )
                    )
                }
                socialDao.putAlarmLink(link.copy(revision = remote.revision))
            }
        }

        val synchronizedActivity = response.activity.map {
            SocialActivity(
                it.id,
                it.alarmId,
                it.groupId,
                it.alarmRevision,
                it.deviceId,
                it.deviceName,
                AlarmActivityKind.valueOf(it.kind.uppercase()),
                it.occurredAt
            )
        }
        socialDatabase.withTransaction {
            socialDao.clearMembers()
            socialDao.clearDeliveries()
            socialDao.clearActivity()
            socialDao.clearGroups()
            socialDao.putGroups(response.groups.map {
                SocialGroup(
                    it.id,
                    it.name,
                    AlarmPermission.valueOf(it.alarmPermission.uppercase()),
                    it.notifySnoozed,
                    it.notifyDismissed,
                    MemberRole.valueOf(it.role.uppercase())
                )
            })
            socialDao.putMembers(response.members.map {
                SocialMember(
                    it.groupId,
                    it.deviceId,
                    it.name,
                    MemberRole.valueOf(it.role.uppercase())
                )
            })
            socialDao.putDeliveries(response.deliveries.map {
                SharedAlarmDelivery(
                    it.alarmId,
                    it.deviceId,
                    it.revision,
                    it.deliveredAt
                )
            })
            socialDao.putActivity(synchronizedActivity)
        }

        val newActivity = synchronizedActivity.filter { it.id !in previousActivityIds }
        Preferences.edit { putLong(Preferences.jaySyncCursorKey, response.cursor) }
        SocialSyncResult(
            if (previousCursor == 0L) emptyList() else newActivity,
            identity.id
        )
    }

    suspend fun createGroup(
        name: String,
        permission: AlarmPermission,
        notifySnoozed: Boolean,
        notifyDismissed: Boolean
    ) = withContext(Dispatchers.IO) {
        val serverUrl = Preferences.instance.getString(
            Preferences.jayServerUrlKey,
            DEFAULT_SERVER_URL
        ) ?: DEFAULT_SERVER_URL
        val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
        SocialApi(serverUrl, identity).apply {
            register()
            createGroup(
                GroupCreate(
                    name,
                    permission.name.lowercase(),
                    notifySnoozed,
                    notifyDismissed
                )
            )
        }
        synchronize()
    }

    suspend fun updateGroup(group: SocialGroup) = withContext(Dispatchers.IO) {
        val serverUrl = Preferences.instance.getString(
            Preferences.jayServerUrlKey,
            DEFAULT_SERVER_URL
        ) ?: DEFAULT_SERVER_URL
        val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
        SocialApi(serverUrl, identity).updateGroup(
            group.id,
            GroupUpdate(
                group.name,
                group.alarmPermission.name.lowercase(),
                group.notifySnoozed,
                group.notifyDismissed
            )
        )
        synchronize()
    }

    suspend fun createInvite(groupId: String): String = withContext(Dispatchers.IO) {
        val serverUrl = Preferences.instance.getString(
            Preferences.jayServerUrlKey,
            DEFAULT_SERVER_URL
        ) ?: DEFAULT_SERVER_URL
        val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
        val invitation = SocialApi(serverUrl, identity).createInvite(groupId)
        "jay://join?server=${java.net.URLEncoder.encode(serverUrl, Charsets.UTF_8.name())}" +
                "&token=${java.net.URLEncoder.encode(invitation.token, Charsets.UTF_8.name())}"
    }

    suspend fun joinGroup(invitation: String) = withContext(Dispatchers.IO) {
        val uri = URI(invitation)
        val parameters = uri.rawQuery.orEmpty().split('&').mapNotNull {
            val parts = it.split('=', limit = 2)
            if (parts.size == 2) parts[0] to java.net.URLDecoder.decode(
                parts[1],
                Charsets.UTF_8.name()
            ) else null
        }.toMap()
        val serverUrl = parameters["server"] ?: Preferences.instance.getString(
            Preferences.jayServerUrlKey,
            DEFAULT_SERVER_URL
        ) ?: DEFAULT_SERVER_URL
        val token = parameters["token"] ?: invitation
        val configuredServer = Preferences.instance.getString(
            Preferences.jayServerUrlKey,
            DEFAULT_SERVER_URL
        ) ?: DEFAULT_SERVER_URL
        require(serverUrl.trimEnd('/') == configuredServer.trimEnd('/')) {
            "This invitation belongs to $serverUrl. Change your Jay server in settings first."
        }
        val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
        SocialApi(serverUrl, identity).apply {
            register()
            joinGroup(token)
        }
        synchronize()
    }

    suspend fun leaveGroup(groupId: String) = withContext(Dispatchers.IO) {
        val serverUrl = Preferences.instance.getString(
            Preferences.jayServerUrlKey,
            DEFAULT_SERVER_URL
        ) ?: DEFAULT_SERVER_URL
        val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
        SocialApi(serverUrl, identity).leaveGroup(groupId)
        synchronize()
    }

    suspend fun updateMember(groupId: String, deviceId: String, role: MemberRole) =
        withContext(Dispatchers.IO) {
            val serverUrl = Preferences.instance.getString(
                Preferences.jayServerUrlKey,
                DEFAULT_SERVER_URL
            ) ?: DEFAULT_SERVER_URL
            val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
            SocialApi(serverUrl, identity).updateMember(
                groupId,
                deviceId,
                role.name.lowercase()
            )
            synchronize()
        }

    suspend fun removeMember(groupId: String, deviceId: String) = withContext(Dispatchers.IO) {
        val serverUrl = Preferences.instance.getString(
            Preferences.jayServerUrlKey,
            DEFAULT_SERVER_URL
        ) ?: DEFAULT_SERVER_URL
        val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
        SocialApi(serverUrl, identity).removeMember(groupId, deviceId)
        synchronize()
    }

    suspend fun createSharedAlarm(groupId: String, alarm: Alarm): Long =
        withContext(Dispatchers.IO) {
            val serverUrl = Preferences.instance.getString(
                Preferences.jayServerUrlKey,
                DEFAULT_SERVER_URL
            ) ?: DEFAULT_SERVER_URL
            val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
            val response = SocialApi(serverUrl, identity).createAlarm(
                SharedAlarmRequest(
                    groupId,
                    alarm.time,
                    alarm.label,
                    alarm.enabled,
                    alarm.days,
                    alarm.vibrate,
                    alarm.repeat,
                    alarm.snoozeEnabled,
                    alarm.snoozeMinutes,
                    alarm.soundEnabled,
                    alarm.vibrationPattern,
                    alarm.vibrationPatternName
                )
            )
            val localId = alarmUseCase.createAlarm(alarm)
            socialDao.putAlarmLink(
                SharedAlarmLink(response.id, localId, groupId, response.revision ?: 1)
            )
            localId
        }

    suspend fun updateAlarm(alarm: Alarm) = withContext(Dispatchers.IO) {
        val link = socialDao.getAlarmLinkByLocalId(alarm.id)
        if (link == null) {
            alarmUseCase.updateAlarm(alarm)
        } else {
            val serverUrl = Preferences.instance.getString(
                Preferences.jayServerUrlKey,
                DEFAULT_SERVER_URL
            ) ?: DEFAULT_SERVER_URL
            val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
            val response = SocialApi(serverUrl, identity).updateAlarm(
                link.remoteAlarmId,
                SharedAlarmRequest(
                    time = alarm.time,
                    label = alarm.label,
                    enabled = alarm.enabled,
                    days = alarm.days,
                    vibrate = alarm.vibrate,
                    repeat = alarm.repeat,
                    snoozeEnabled = alarm.snoozeEnabled,
                    snoozeMinutes = alarm.snoozeMinutes,
                    soundEnabled = alarm.soundEnabled,
                    vibrationPattern = alarm.vibrationPattern,
                    vibrationPatternName = alarm.vibrationPatternName,
                    expectedRevision = link.revision
                )
            )
            context.sendBroadcast(
                Intent(AlarmService.CANCEL_SHARED_ALARM_INTENT_ACTION)
                    .setPackage(context.packageName)
                    .putExtra(AlarmHelper.EXTRA_ID, alarm.id)
            )
            alarmUseCase.updateAlarm(alarm)
            socialDao.putAlarmLink(link.copy(revision = response.revision ?: link.revision + 1))
        }
    }

    suspend fun deleteAlarm(alarm: Alarm) = withContext(Dispatchers.IO) {
        val link = socialDao.getAlarmLinkByLocalId(alarm.id)
        if (link != null) {
            val serverUrl = Preferences.instance.getString(
                Preferences.jayServerUrlKey,
                DEFAULT_SERVER_URL
            ) ?: DEFAULT_SERVER_URL
            val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
            SocialApi(serverUrl, identity).deleteAlarm(link.remoteAlarmId, link.revision)
            context.sendBroadcast(
                Intent(AlarmService.CANCEL_SHARED_ALARM_INTENT_ACTION)
                    .setPackage(context.packageName)
                    .putExtra(AlarmHelper.EXTRA_ID, alarm.id)
            )
            socialDao.deleteAlarmLink(link.remoteAlarmId)
        }
        alarmUseCase.deleteAlarm(alarm)
    }

    suspend fun recordActivity(localAlarmId: Long, kind: AlarmActivityKind) =
        withContext(Dispatchers.IO) {
            val link = socialDao.getAlarmLinkByLocalId(localAlarmId) ?: return@withContext
            val serverUrl = Preferences.instance.getString(
                Preferences.jayServerUrlKey,
                DEFAULT_SERVER_URL
            ) ?: DEFAULT_SERVER_URL
            val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
            SocialApi(serverUrl, identity).recordActivity(
                link.remoteAlarmId,
                AlarmActivityRequest(
                    link.revision,
                    kind.name.lowercase(),
                    Instant.now().toString()
                )
            )
        }

    suspend fun renameDevice(name: String) = withContext(Dispatchers.IO) {
        val serverUrl = Preferences.instance.getString(
            Preferences.jayServerUrlKey,
            DEFAULT_SERVER_URL
        ) ?: DEFAULT_SERVER_URL
        Preferences.edit { putString(Preferences.jayDeviceNameKey, name) }
        val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
        SocialApi(serverUrl, identity).apply {
            register()
            updateDevice(name)
        }
        synchronize()
    }

    suspend fun registerPushToken(token: String) = withContext(Dispatchers.IO) {
        val serverUrl = Preferences.instance.getString(
            Preferences.jayServerUrlKey,
            DEFAULT_SERVER_URL
        ) ?: DEFAULT_SERVER_URL
        val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
        SocialApi(serverUrl, identity).apply {
            register()
            updatePushToken(token)
        }
    }

    suspend fun changeServer(serverUrl: String) = withContext(Dispatchers.IO) {
        URI(serverUrl).toURL()
        socialDao.getAlarmLinks().forEach { link ->
            alarmRepository.getAlarmById(link.localAlarmId)?.let {
                context.sendBroadcast(
                    Intent(AlarmService.CANCEL_SHARED_ALARM_INTENT_ACTION)
                        .setPackage(context.packageName)
                        .putExtra(AlarmHelper.EXTRA_ID, it.id)
                )
                alarmUseCase.deleteAlarm(it)
            }
            socialDao.deleteAlarmLink(link.remoteAlarmId)
        }
        socialDatabase.withTransaction {
            socialDao.clearMembers()
            socialDao.clearDeliveries()
            socialDao.clearActivity()
            socialDao.clearGroups()
        }
        Preferences.edit {
            putString(Preferences.jayServerUrlKey, serverUrl.trimEnd('/'))
            putLong(Preferences.jaySyncCursorKey, 0)
        }
    }

    companion object {
        const val DEFAULT_SERVER_URL = "https://jay-server.onrender.com"
    }
}
