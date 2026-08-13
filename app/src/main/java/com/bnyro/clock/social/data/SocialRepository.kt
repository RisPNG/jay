package com.bnyro.clock.social.data

import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.room.withTransaction
import androidx.work.WorkManager
import com.bnyro.clock.BuildConfig
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.repository.AlarmRepository
import com.bnyro.clock.domain.usecase.CreateUpdateDeleteAlarmUseCase
import com.bnyro.clock.social.domain.AlarmActivityKind
import com.bnyro.clock.social.domain.AlarmActivityRequest
import com.bnyro.clock.social.domain.AlarmOccurrenceSchedule
import com.bnyro.clock.social.domain.AlarmPermission
import com.bnyro.clock.social.domain.SocialActivityPage
import com.bnyro.clock.social.domain.GroupCreate
import com.bnyro.clock.social.domain.GroupUpdate
import com.bnyro.clock.social.domain.MemberRole
import com.bnyro.clock.social.domain.MemberNotificationUpdate
import com.bnyro.clock.social.domain.SharedAlarmLink
import com.bnyro.clock.social.domain.SharedAlarmRequest
import com.bnyro.clock.social.domain.SocialChange
import com.bnyro.clock.social.domain.SocialGroup
import com.bnyro.clock.social.domain.SocialMember
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.services.AlarmService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import java.net.URI
import java.security.MessageDigest
import java.time.Instant

data class SocialSyncResult(
    val changes: List<SocialChange>,
    val groups: Map<String, SocialGroup>,
    val deviceId: String
)

class SocialRepository(
    private val context: Context,
    private val socialDatabase: SocialDatabase,
    private val alarmRepository: AlarmRepository
) {
    private val socialDao = socialDatabase.socialDao()
    private val alarmUseCase = CreateUpdateDeleteAlarmUseCase(context, alarmRepository)
    private val synchronizationMutex = Mutex()

    val groups: Flow<List<SocialGroup>> = socialDao.getGroupsStream()
    val members: Flow<List<SocialMember>> = socialDao.getMembersStream()
    val alarmGroupNames = socialDao.getAlarmGroupNamesStream()

    suspend fun synchronize(): SocialSyncResult = synchronizationMutex.withLock {
        withContext(Dispatchers.IO) {
            val serverUrl = Preferences.instance.getString(
                Preferences.jayServerUrlKey,
                DEFAULT_SERVER_URL
            ) ?: DEFAULT_SERVER_URL
            val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
            val api = SocialApi(serverUrl, identity)
            api.register()
            val previousCursor = Preferences.instance.getLong(Preferences.jaySyncCursorKey, 0)
            val knownGroupIds = groups.first().map { it.id }.toSet()
            val response = api.synchronize(previousCursor)
            val remoteGroupIds = response.groups.map { it.id }.toSet()
            val remoteAlarmIds = response.alarms.map { it.id }.toSet()

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
                WorkManager.getInstance(context).cancelUniqueWork(
                    "jay_ignored_alarm_${link.localAlarmId}"
                )
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
                        WorkManager.getInstance(context).cancelUniqueWork(
                            "jay_ignored_alarm_${it.localAlarmId}"
                        )
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
                    alarmRepository.getAlarmById(localId)?.let {
                        scheduleIgnoredOutcome(it, remote.id, remote.revision)
                    }
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
                    alarmRepository.getAlarmById(link.localAlarmId)?.let {
                        scheduleIgnoredOutcome(it, remote.id, remote.revision)
                    }
                }
            }

            val synchronizedGroups = response.groups.map {
                SocialGroup(
                    it.id,
                    it.name,
                    AlarmPermission.valueOf(it.alarmPermission.uppercase()),
                    it.notifyAlarmChanges,
                    it.notifySnoozed,
                    it.notifyDismissed,
                    it.notifyIgnored,
                    it.notifyMembership,
                    it.notifyAdministrative,
                    MemberRole.valueOf(it.role.uppercase())
                )
            }
            socialDatabase.withTransaction {
                socialDao.clearMembers()
                socialDao.clearGroups()
                socialDao.putGroups(synchronizedGroups)
                socialDao.putMembers(response.members.map {
                    SocialMember(
                        it.groupId,
                        it.deviceId,
                        it.name,
                        MemberRole.valueOf(it.role.uppercase())
                    )
                })
            }

            Preferences.edit { putLong(Preferences.jaySyncCursorKey, response.cursor) }
            SocialSyncResult(
                if (previousCursor == 0L) emptyList() else response.changes.map {
                    SocialChange(
                        it.sequence,
                        it.groupId,
                        it.groupName,
                        it.entityType,
                        it.entityId,
                        it.action,
                        it.entityLabel,
                        it.entityTime,
                        it.actorDeviceId,
                        it.actorName,
                        it.subjectDeviceId,
                        it.subjectName,
                        it.recipientDeviceId,
                        it.details,
                        it.occurredAt
                    )
                }.filter { it.groupId in knownGroupIds },
                synchronizedGroups.associateBy { it.id },
                identity.id
            )
        }
    }

    suspend fun followLiveChanges(onSynchronized: (SocialSyncResult) -> Unit) {
        val serverUrl = Preferences.instance.getString(
            Preferences.jayServerUrlKey,
            DEFAULT_SERVER_URL
        ) ?: DEFAULT_SERVER_URL
        val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
        SocialApi(serverUrl, identity).apply {
            register()
            listenForChanges(
                shouldContinue = {
                    val configuredServer = Preferences.instance.getString(
                        Preferences.jayServerUrlKey,
                        DEFAULT_SERVER_URL
                    ) ?: DEFAULT_SERVER_URL
                    configuredServer.trimEnd('/') == serverUrl.trimEnd('/')
                },
                onChange = { onSynchronized(synchronize()) }
            )
        }
    }

    suspend fun createGroup(
        name: String,
        permission: AlarmPermission,
        notifyAlarmChanges: Boolean,
        notifySnoozed: Boolean,
        notifyDismissed: Boolean,
        notifyIgnored: Boolean
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
                    notifyAlarmChanges,
                    notifySnoozed,
                    notifyDismissed,
                    notifyIgnored
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
                group.notifyAlarmChanges,
                group.notifySnoozed,
                group.notifyDismissed,
                group.notifyIgnored
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

    suspend fun updateMemberNotificationSettings(group: SocialGroup) =
        withContext(Dispatchers.IO) {
            val serverUrl = Preferences.instance.getString(
                Preferences.jayServerUrlKey,
                DEFAULT_SERVER_URL
            ) ?: DEFAULT_SERVER_URL
            val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
            SocialApi(serverUrl, identity).updateMemberNotificationSettings(
                group.id,
                MemberNotificationUpdate(
                    group.notifyMembership,
                    group.notifyAdministrative
                )
            )
            synchronize()
        }

    suspend fun getGroupActivity(groupId: String, before: Long? = null): SocialActivityPage =
        withContext(Dispatchers.IO) {
            val serverUrl = Preferences.instance.getString(
                Preferences.jayServerUrlKey,
                DEFAULT_SERVER_URL
            ) ?: DEFAULT_SERVER_URL
            val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
            val page = SocialApi(serverUrl, identity).getGroupActivity(groupId, before)
            SocialActivityPage(
                page.items.map {
                    SocialChange(
                        it.sequence,
                        it.groupId,
                        it.groupName,
                        it.entityType,
                        it.entityId,
                        it.action,
                        it.entityLabel,
                        it.entityTime,
                        it.actorDeviceId,
                        it.actorName,
                        it.subjectDeviceId,
                        it.subjectName,
                        it.recipientDeviceId,
                        it.details,
                        it.occurredAt
                    )
                },
                page.nextBefore
            )
        }

    suspend fun getAlarmActivity(alarmId: String, before: Long? = null): SocialActivityPage =
        withContext(Dispatchers.IO) {
            val serverUrl = Preferences.instance.getString(
                Preferences.jayServerUrlKey,
                DEFAULT_SERVER_URL
            ) ?: DEFAULT_SERVER_URL
            val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
            val page = SocialApi(serverUrl, identity).getAlarmActivity(alarmId, before)
            SocialActivityPage(
                page.items.map {
                    SocialChange(
                        it.sequence,
                        it.groupId,
                        it.groupName,
                        it.entityType,
                        it.entityId,
                        it.action,
                        it.entityLabel,
                        it.entityTime,
                        it.actorDeviceId,
                        it.actorName,
                        it.subjectDeviceId,
                        it.subjectName,
                        it.recipientDeviceId,
                        it.details,
                        it.occurredAt
                    )
                },
                page.nextBefore
            )
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
        synchronizationMutex.withLock {
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
                alarmRepository.getAlarmById(localId)?.let {
                    scheduleIgnoredOutcome(it, response.id, response.revision ?: 1)
                }
                localId
            }
        }

    suspend fun updateAlarm(alarm: Alarm) = synchronizationMutex.withLock {
        withContext(Dispatchers.IO) {
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
                val revision = response.revision ?: link.revision + 1
                socialDao.putAlarmLink(link.copy(revision = revision))
                scheduleIgnoredOutcome(alarm, link.remoteAlarmId, revision)
            }
        }
    }

    suspend fun deleteAlarm(alarm: Alarm) = synchronizationMutex.withLock {
        withContext(Dispatchers.IO) {
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
                WorkManager.getInstance(context).cancelUniqueWork("jay_ignored_alarm_${alarm.id}")
            }
            alarmUseCase.deleteAlarm(alarm)
        }
    }

    suspend fun recordActivity(
        localAlarmId: Long,
        kind: AlarmActivityKind,
        eventId: String,
        occurredAt: String,
        occurrenceId: String?,
        reason: String?
    ) =
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
                    eventId,
                    link.revision,
                    kind.name.lowercase(),
                    occurredAt,
                    occurrenceId,
                    reason
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

    suspend fun refreshPlayEntitlement() = withContext(Dispatchers.IO) {
        val serverUrl = Preferences.instance.getString(
            Preferences.jayServerUrlKey,
            DEFAULT_SERVER_URL
        ) ?: DEFAULT_SERVER_URL
        val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
        val requestHash = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(
                "jay-play-entitlement:${identity.id}".toByteArray()
            ),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val integrityManager = IntegrityManagerFactory.createStandard(context)
        val tokenProvider = Tasks.await(
            integrityManager.prepareIntegrityToken(
                StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(BuildConfig.JAY_PLAY_CLOUD_PROJECT_NUMBER)
                    .build()
            )
        )
        val integrityToken = Tasks.await(
            tokenProvider.request(
                StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                    .setRequestHash(requestHash)
                    .build()
            )
        ).token()
        SocialApi(serverUrl, identity).run {
            register()
            updatePlayEntitlement(integrityToken)
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
            WorkManager.getInstance(context).cancelUniqueWork(
                "jay_ignored_alarm_${link.localAlarmId}"
            )
        }
        socialDatabase.withTransaction {
            socialDao.clearMembers()
            socialDao.clearGroups()
        }
        Preferences.edit {
            putString(Preferences.jayServerUrlKey, serverUrl.trimEnd('/'))
            putLong(Preferences.jaySyncCursorKey, 0)
        }
    }

    private suspend fun scheduleIgnoredOutcome(
        alarm: Alarm,
        remoteAlarmId: String,
        revision: Int
    ) {
        if (!alarm.enabled) {
            WorkManager.getInstance(context).cancelUniqueWork("jay_ignored_alarm_${alarm.id}")
            return
        }
        val triggerAt = AlarmHelper.getAlarmTime(alarm)
        val occurrenceId = triggerAt.toString()
        SocialIgnoredAlarmWorker.schedule(
            context,
            alarm.id,
            triggerAt,
            occurrenceId
        )
        val serverUrl = Preferences.instance.getString(
            Preferences.jayServerUrlKey,
            DEFAULT_SERVER_URL
        ) ?: DEFAULT_SERVER_URL
        val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
        SocialApi(serverUrl, identity).registerAlarmOccurrence(
            remoteAlarmId,
            AlarmOccurrenceSchedule(
                revision,
                occurrenceId,
                Instant.ofEpochMilli(triggerAt).toString(),
                Instant.ofEpochMilli(
                    triggerAt + Preferences.instance.getInt(
                        Preferences.alarmTimeoutMinutesKey,
                        AlarmService.ALARM_TIMEOUT_MINUTES
                    ) * 60_000L
                ).toString()
            )
        )
    }

    companion object {
        const val DEFAULT_SERVER_URL = "https://jay.poppybit.com"
    }
}
