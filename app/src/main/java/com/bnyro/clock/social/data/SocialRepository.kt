package com.bnyro.clock.social.data

import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.core.content.edit
import androidx.room.withTransaction
import androidx.work.WorkManager
import com.bnyro.clock.BuildConfig
import com.bnyro.clock.R
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.model.RepeatAnchor
import com.bnyro.clock.domain.model.RepeatUnit
import com.bnyro.clock.domain.model.TimerSettings
import com.bnyro.clock.domain.repository.AlarmRepository
import com.bnyro.clock.domain.usecase.CreateUpdateDeleteAlarmUseCase
import com.bnyro.clock.social.domain.AlarmActivityKind
import com.bnyro.clock.social.domain.AlarmActivityRequest
import com.bnyro.clock.social.domain.AlarmOccurrenceSchedule
import com.bnyro.clock.social.domain.AlarmPermission
import com.bnyro.clock.social.domain.AlarmTimeBasis
import com.bnyro.clock.social.domain.DeviceCapabilities
import com.bnyro.clock.social.domain.DeviceUpdate
import com.bnyro.clock.social.domain.DismissedSharedTimer
import com.bnyro.clock.social.domain.GroupCreate
import com.bnyro.clock.social.domain.GroupUpdate
import com.bnyro.clock.social.domain.InviteJoin
import com.bnyro.clock.social.domain.InviteResponse
import com.bnyro.clock.social.domain.MemberNotificationUpdate
import com.bnyro.clock.social.domain.MemberRole
import com.bnyro.clock.social.domain.MembershipAccessDto
import com.bnyro.clock.social.domain.PendingOperation
import com.bnyro.clock.social.domain.PushTokenUpdate
import com.bnyro.clock.social.domain.ScopeSyncItem
import com.bnyro.clock.social.domain.SharedAlarmDto
import com.bnyro.clock.social.domain.SharedAlarmLink
import com.bnyro.clock.social.domain.SharedAlarmRequest
import com.bnyro.clock.social.domain.SharedSoundDto
import com.bnyro.clock.social.domain.SharedSoundMode
import com.bnyro.clock.social.domain.SharedSoundProgress
import com.bnyro.clock.social.domain.SharedSoundSelection
import com.bnyro.clock.social.domain.SharedTimerDto
import com.bnyro.clock.social.domain.SharedTimerRequest
import com.bnyro.clock.social.domain.SocialActivityPage
import com.bnyro.clock.social.domain.SocialChange
import com.bnyro.clock.social.domain.SocialChangeDto
import com.bnyro.clock.social.domain.SocialGroup
import com.bnyro.clock.social.domain.SocialGroupDto
import com.bnyro.clock.social.domain.SocialMember
import com.bnyro.clock.social.domain.SocialMemberDto
import com.bnyro.clock.social.domain.SocialOccurrenceDto
import com.bnyro.clock.social.domain.canEditAlarms
import com.bnyro.clock.social.presentation.SocialNotificationHelper
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.Preferences
import com.bnyro.clock.util.services.AlarmService
import com.bnyro.clock.util.services.TimerService
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    private val synchronization = SocialSynchronization(context, socialDatabase)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var registeredIdentity: Pair<String, String>? = null

    val groups: Flow<List<SocialGroup>> = socialDao.getGroupsStream()
    val members: Flow<List<SocialMember>> = socialDao.getMembersStream()
    val alarmGroupNames = socialDao.getAlarmGroupNamesStream()
    val rejectedOperations = socialDao.getRejectedOperationsStream()
    val readyInvitations = socialDao.getReadyInvitationsStream()

    suspend fun acknowledgeOperation(operation: PendingOperation) {
        socialDao.resolveOperation(operation.operationId, "reviewed", operation.rejectionCode, operation.response)
    }

    val deviceCapabilities: DeviceCapabilities
        get() {
            val serverUrl = Preferences.instance.getString(
                SocialPreferences.serverUrlKey,
                DEFAULT_SERVER_URL
            ) ?: DEFAULT_SERVER_URL
            val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
            if (Preferences.instance.getString(SocialPreferences.capabilitiesServerKey, null) != serverUrl ||
                Preferences.instance.getString(SocialPreferences.capabilitiesDeviceKey, null) != identity.id
            ) return DeviceCapabilities()
            return Preferences.instance.getString(SocialPreferences.capabilitiesKey, null)
                ?.let { Json.decodeFromString<DeviceCapabilities>(it) } ?: DeviceCapabilities()
        }

    val canUploadSharedSounds: Boolean
        get() = deviceCapabilities.canUploadSharedSounds()

    private fun applyDeviceCapabilities(
        capabilities: DeviceCapabilities,
        serverUrl: String,
        deviceId: String
    ) {
        val currentServer = Preferences.instance.getString(
            SocialPreferences.serverUrlKey,
            DEFAULT_SERVER_URL
        ) ?: DEFAULT_SERVER_URL
        if (currentServer != serverUrl ||
            DeviceIdentityStore.loadOrCreate(context, currentServer).id != deviceId
        ) return
        val previous = deviceCapabilities
        Preferences.edit {
            putString(SocialPreferences.capabilitiesKey, Json.encodeToString(capabilities))
            putString(SocialPreferences.capabilitiesServerKey, serverUrl)
            putString(SocialPreferences.capabilitiesDeviceKey, deviceId)
        }
        if (capabilities.canUploadSharedSounds()) {
            androidx.core.app.NotificationManagerCompat.from(context).cancel(
                SocialNotificationHelper.ENTITLEMENT_NOTIFICATION_ID
            )
        } else if (previous.requiresPlayEntitlement && previous.sharedSoundUpload) {
            SocialNotificationHelper.notifyDeviceIssue(
                context,
                SocialNotificationHelper.ENTITLEMENT_NOTIFICATION_ID,
                context.getString(R.string.play_entitlement_lost_title),
                context.getString(R.string.play_entitlement_lost_message)
            )
        }
    }

    suspend fun synchronize(dirtyScopes: Set<String>? = null): SocialSyncResult = synchronizationMutex.withLock {
        withContext(Dispatchers.IO) {
            val serverUrl = Preferences.instance.getString(
                SocialPreferences.serverUrlKey,
                DEFAULT_SERVER_URL
            ) ?: DEFAULT_SERVER_URL
            val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
            val api = SocialApi(serverUrl, identity)
            if (registeredIdentity != (serverUrl to identity.id)) {
                api.register()
                registeredIdentity = serverUrl to identity.id
            }
            val hadCheckpoint = socialDao.getScope("/v1/sync")?.cursor != null
            val knownGroupIds = groups.first().map { it.id }.toSet()
            val pending = socialDao.getPendingOperations()
            synchronization.flushOperations(api)
            val identityScope = socialDao.getScope("/v1/sync")?.scopeId
            if (dirtyScopes == null || identityScope == null || identityScope in dirtyScopes || pending.isNotEmpty())
                synchronization.synchronizeScope(api, "/v1/sync")
            val memberships = socialDao.getResources().filter { it.kind == "membership" }
                .map { json.decodeFromString<MembershipAccessDto>(it.representation) }
            for (scope in socialDao.getScopes().filter { it.route != "/v1/sync" && memberships.none { member -> member.scopeId == it.scopeId } }) {
                socialDatabase.withTransaction {
                    socialDao.clearScopeResources(scope.scopeId)
                    socialDao.clearStaging(scope.scopeId)
                    socialDao.deleteScope(scope.route)
                }
            }
            for (membership in memberships) {
                if (dirtyScopes != null && membership.scopeId !in dirtyScopes &&
                    pending.none { it.scopeId == membership.scopeId } &&
                    socialDao.getScope("/v1/groups/${membership.groupId}/sync")?.cursor != null) continue
                try {
                    synchronization.synchronizeScope(api, "/v1/groups/${membership.groupId}/sync")
                } catch (error: SocialApiException) {
                    if (error.status != 404 && error.status != 403) throw error
                    socialDatabase.withTransaction {
                        socialDao.clearScopeResources(membership.scopeId)
                        socialDao.clearStaging(membership.scopeId)
                        socialDao.deleteScope("/v1/groups/${membership.groupId}/sync")
                    }
                }
            }
            applyDeviceCapabilities(api.deviceCapabilities(), serverUrl, identity.id)
            socialDao.confirmAcceptedOperations()
            val changes = socialDao.getPendingActivity().map {
                ScopeSyncItem("activity", it.key, "upsert", Json.parseToJsonElement(it.representation).jsonObject, 0, 0)
            }
            val result = applySharedState(identity.id, changes, hadCheckpoint, knownGroupIds)
            SocialNotificationHelper.notifySocialChanges(context, result)
            socialDao.clearPendingActivity()
            result
        }
    }

    private suspend fun applySharedState(
        identityId: String,
        changes: List<ScopeSyncItem> = emptyList(),
        hadCheckpoint: Boolean = false,
        knownGroupIds: Set<String> = emptySet()
    ): SocialSyncResult {
        val resources = synchronization.projectedResources()
        if (socialDao.getProjectedOperations().none { it.kind == "identity" && it.method == "PATCH" }) {
            resources.firstOrNull { it.kind == "identity" }?.let {
                val name = Json.parseToJsonElement(it.representation).jsonObject.getValue("name").jsonPrimitive.content
                Preferences.edit { putString(SocialPreferences.deviceNameKey, name) }
            }
        }
        val sounds = resources.filter { it.kind == "sound" }
            .associate { val sound = json.decodeFromString<SharedSoundDto>(it.representation); sound.id to sound }
        val access = resources.filter { it.kind == "membership" }.map { json.decodeFromString<MembershipAccessDto>(it.representation) }.associateBy { it.groupId }
        val synchronizedGroups = resources.filter { it.kind == "group" }.mapNotNull {
            val group = json.decodeFromString<SocialGroupDto>(it.representation)
            val membership = access[group.id] ?: return@mapNotNull null
            SocialGroup(
                group.id, group.name, AlarmPermission.valueOf(group.alarmPermission.uppercase()),
                group.notifyAlarmChanges, group.notifySnoozed, group.notifyDismissed, group.notifyIgnored,
                membership.notifyMembership, membership.notifyAdministrative, MemberRole.valueOf(membership.role.uppercase()),
                AlarmTimeBasis.valueOf(group.alarmTimeBasis.uppercase()), group.alarmTimeZone, group.sharedAnswers,
                membership.id
            )
        }
        val remoteGroups = synchronizedGroups.associateBy { it.id }
        val remoteGroupIds = remoteGroups.keys
        val remoteAlarms = resources.filter { it.kind == "alarm" }.map {
            json.decodeFromString<SharedAlarmDto>(it.representation)
        }.filter { it.groupId in remoteGroupIds }.map { it.copy(soundTitle = sounds[it.soundId]?.title ?: it.soundTitle) }
        val remoteTimers = resources.filter { it.kind == "timer" }.map {
            json.decodeFromString<SharedTimerDto>(it.representation)
        }.filter { it.groupId in remoteGroupIds }.map { it.copy(soundTitle = sounds[it.soundId]?.title ?: it.soundTitle) }
        val remoteOccurrences = resources.filter { it.kind == "occurrence" }.map {
            json.decodeFromString<SocialOccurrenceDto>(it.representation)
        }
        val remoteMembers = resources.filter { it.kind == "member" }.map {
            json.decodeFromString<SocialMemberDto>(it.representation)
        }.filter { it.groupId in remoteGroupIds }
        val remoteChanges = changes.map { json.decodeFromJsonElement<SocialChangeDto>(it.data) }
        val soundStore = SharedSoundStore(context)
        val remoteAlarmIds = remoteAlarms.map { it.id }.toSet()

        socialDatabase.withTransaction {
            socialDao.clearMembers()
            socialDao.clearGroups()
            socialDao.putGroups(synchronizedGroups)
            socialDao.putMembers(remoteMembers.map {
                SocialMember(
                    it.groupId,
                    it.deviceId,
                    it.name,
                    MemberRole.valueOf(it.role.uppercase())
                )
            })
        }

        socialDao.getAlarmLinks().filter {
            it.groupId !in remoteGroupIds || it.remoteAlarmId !in remoteAlarmIds
        }.forEach { deleteSharedAlarmLink(it) }

        remoteAlarms.forEach { remote ->
            val link = socialDao.getAlarmLinkByRemoteId(remote.id)
            val soundMode = SharedSoundMode.valueOf(remote.soundMode.uppercase())
            val soundFile = remote.soundId?.takeIf { soundMode == SharedSoundMode.SHARED }
                ?.let(soundStore::cached)
            val soundUri = soundFile?.toURI()?.toString()
            val timeZone = remoteGroups[remote.groupId]?.takeIf {
                it.alarmTimeBasis == AlarmTimeBasis.GROUP_TIME_ZONE
            }?.alarmTimeZone
            if (link == null) {
                val localId = alarmUseCase.createAlarm(
                    Alarm(
                        time = remote.time,
                        label = remote.label,
                        labelColor = remote.labelColor,
                        enabled = remote.enabled,
                        days = remote.days,
                        vibrate = remote.vibrate,
                        startDate = LocalDate.parse(remote.startDate).toEpochDay(),
                        repeatInterval = remote.repeatInterval,
                        repeatUnit = RepeatUnit.valueOf(remote.repeatUnit),
                        repeatAnchor = RepeatAnchor.valueOf(remote.repeatAnchor),
                        repeatDuration = remote.repeatDuration,
                        repeatDurationUnit = RepeatUnit.valueOf(remote.repeatDurationUnit),
                        endDate = remote.endDate?.let { LocalDate.parse(it).toEpochDay() },
                        endOccurrences = remote.endOccurrences,
                        advanced = remote.advanced,
                        snoozeEnabled = remote.snoozeEnabled,
                        snoozeMinutes = remote.snoozeMinutes,
                        soundEnabled = soundMode != SharedSoundMode.OFF,
                        soundName = remote.soundTitle,
                        soundUri = soundUri,
                        vibrationPattern = remote.vibrationPattern,
                        vibrationPatternName = remote.vibrationPatternName
                    ),
                    timeZone?.let(ZoneId::of) ?: ZoneId.systemDefault()
                )
                socialDao.putAlarmLink(
                    SharedAlarmLink(
                        remote.id,
                        localId,
                        remote.groupId,
                        remote.revision,
                        soundMode,
                        remote.soundId,
                        remote.soundTitle,
                        timeZone,
                        remote.saveId
                    )
                )
                SocialAlarmSchedule.setTimeZone(localId, timeZone)
                alarmRepository.getAlarmById(localId)?.let {
                    scheduleIgnoredOutcome(it, remote.id, remote.revision)
                }
            } else if (remote.revision != link.revision || remote.saveId != link.saveId || link.timeZone != timeZone) {
                alarmRepository.getAlarmById(link.localAlarmId)?.let { local ->
                    SocialAlarmSchedule.setTimeZone(local.id, timeZone)
                    context.sendBroadcast(
                        Intent(AlarmService.CANCEL_SHARED_ALARM_INTENT_ACTION)
                            .setPackage(context.packageName)
                            .putExtra(AlarmHelper.EXTRA_ID, local.id)
                    )
                    alarmUseCase.updateAlarm(
                        local.copy(
                            time = remote.time,
                            label = remote.label,
                            labelColor = remote.labelColor,
                            enabled = remote.enabled,
                            days = remote.days,
                            vibrate = remote.vibrate,
                            startDate = LocalDate.parse(remote.startDate).toEpochDay(),
                            repeatInterval = remote.repeatInterval,
                            repeatUnit = RepeatUnit.valueOf(remote.repeatUnit),
                            repeatAnchor = RepeatAnchor.valueOf(remote.repeatAnchor),
                            repeatDuration = remote.repeatDuration,
                            repeatDurationUnit = RepeatUnit.valueOf(remote.repeatDurationUnit),
                            endDate = remote.endDate?.let { LocalDate.parse(it).toEpochDay() },
                            endOccurrences = remote.endOccurrences,
                            advanced = remote.advanced,
                            snoozeEnabled = remote.snoozeEnabled,
                            snoozeMinutes = remote.snoozeMinutes,
                            soundEnabled = soundMode != SharedSoundMode.OFF,
                            soundName = remote.soundTitle,
                            soundUri = soundUri,
                            vibrationPattern = remote.vibrationPattern,
                            vibrationPatternName = remote.vibrationPatternName
                        ),
                        timeZone?.let(ZoneId::of) ?: ZoneId.systemDefault()
                    )
                }
                socialDao.putAlarmLink(
                    link.copy(
                        revision = remote.revision,
                        soundMode = soundMode,
                        soundId = remote.soundId,
                        soundTitle = remote.soundTitle,
                        timeZone = timeZone,
                        saveId = remote.saveId
                    )
                )
                alarmRepository.getAlarmById(link.localAlarmId)?.let {
                    scheduleIgnoredOutcome(it, remote.id, remote.revision)
                }
            } else {
                alarmRepository.getAlarmById(link.localAlarmId)?.takeIf { it.soundUri != soundUri }?.let {
                    alarmRepository.updateAlarm(it.copy(soundUri = soundUri))
                }
            }
        }

        // an outcome the server holds for the occurrence this device is living in was
        // answered elsewhere: stop the ring, move the local schedule past it, and arm the
        // next occurrence, whether the answer came from another member or another device
        // carrying this same profile
        remoteOccurrences.groupBy { it.alarmId }.forEach { (remoteAlarmId, rows) ->
            val link = socialDao.getAlarmLinkByRemoteId(remoteAlarmId) ?: return@forEach
            val occurrenceId = Preferences.instance.getString(
                "${SocialPreferences.alarmOccurrencePrefix}${link.localAlarmId}",
                null
            ) ?: return@forEach
            if (
                rows.none {
                    it.occurrenceId == occurrenceId && it.alarmRevision == link.revision &&
                        it.status in RESOLVED_OCCURRENCE_STATUSES
                }
            ) return@forEach
            alarmRepository.getAlarmById(link.localAlarmId)?.let { alarm ->
                context.sendBroadcast(
                    Intent(AlarmService.CANCEL_SHARED_ALARM_INTENT_ACTION)
                        .setPackage(context.packageName)
                        .putExtra(AlarmHelper.EXTRA_ID, alarm.id)
                )
                WorkManager.getInstance(context).cancelUniqueWork(
                    "jay_ignored_alarm_${alarm.id}"
                )
                Preferences.edit {
                    remove("${SocialPreferences.alarmOccurrencePrefix}${alarm.id}")
                }
                if ((occurrenceId.toLongOrNull() ?: 0L) > System.currentTimeMillis()) {
                    alarmUseCase.dismissUpcomingAlarm(alarm)
                } else if (alarm.enabled) {
                    AlarmHelper.enqueue(context, alarm, skipToday = true)
                }
                scheduleIgnoredOutcome(alarm, remoteAlarmId, link.revision)
            }
        }

        // a snooze leaves the shared occurrence pending for the re-ring where it was
        // answered, so the devices sharing this profile only stop ringing
        if (hadCheckpoint) {
            remoteChanges.filter {
                it.entityType == "outcome" && it.action == "snoozed" &&
                    it.subjectDeviceId == identityId
            }.forEach { change ->
                socialDao.getAlarmLinkByRemoteId(change.entityId)?.let { link ->
                    context.sendBroadcast(
                        Intent(AlarmService.CANCEL_SHARED_ALARM_INTENT_ACTION)
                            .setPackage(context.packageName)
                            .putExtra(AlarmHelper.EXTRA_ID, link.localAlarmId)
                    )
                    WorkManager.getInstance(context).cancelUniqueWork(
                        "jay_ignored_alarm_${link.localAlarmId}"
                    )
                }
            }
        }

        applySharedTimers(remoteTimers, synchronizedGroups)
        val provisionalAlarms = socialDao.getProjectedOperations().filter { it.kind == "alarm" }.map { it.targetId }.toSet()
        for (alarm in remoteAlarms.filter { it.id !in provisionalAlarms }) {
            val operationId = UUID.nameUUIDFromBytes("delivery:$identityId:${alarm.id}:${alarm.revision}".toByteArray()).toString()
            synchronization.saveOperation(alarm.groupId, "delivery", alarm.id, "POST", "/v1/alarms/${alarm.id}/deliveries",
                JsonObject(mapOf("revision" to JsonPrimitive(alarm.revision))), null, ordered = false, operationId = operationId)
        }
        if (socialDao.getPendingOperations().isNotEmpty()) SocialSyncWorker.enqueue(context)
        soundStore.prune(
            remoteAlarms.mapNotNull { it.soundId }.toSet() +
                remoteTimers.mapNotNull { it.soundId }.toSet()
        )

        (remoteAlarms.mapNotNull { it.soundId } + remoteTimers.mapNotNull { it.soundId })
            .distinct().filter { soundStore.cached(it) == null && sounds[it]?.status == "ready" }
            .forEach { SharedSoundWorker.enqueue(context, it) }

        socialDao.getAudioOperations().forEach { SharedSoundWorker.upload(context, it) }

        return SocialSyncResult(
            if (!hadCheckpoint) emptyList() else remoteChanges.map {
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
            identityId
        )
    }

    suspend fun refreshSharedState() = synchronizationMutex.withLock {
        withContext(Dispatchers.IO) {
            val server = Preferences.instance.getString(SocialPreferences.serverUrlKey, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
            applySharedState(DeviceIdentityStore.loadOrCreate(context, server).id)
        }
    }

    suspend fun followLiveChanges() = withContext(Dispatchers.IO) {
        val serverUrl = Preferences.instance.getString(
            SocialPreferences.serverUrlKey,
            DEFAULT_SERVER_URL
        ) ?: DEFAULT_SERVER_URL
        val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
        SocialApi(serverUrl, identity).apply {
            synchronizationMutex.withLock {
                if (registeredIdentity != (serverUrl to identity.id)) {
                    register()
                    registeredIdentity = serverUrl to identity.id
                }
            }
            listenForChanges(
                shouldContinue = {
                    val configuredServer = Preferences.instance.getString(
                        SocialPreferences.serverUrlKey,
                        DEFAULT_SERVER_URL
                    ) ?: DEFAULT_SERVER_URL
                    configuredServer.trimEnd('/') == serverUrl.trimEnd('/') &&
                        DeviceIdentityStore.loadOrCreate(context, configuredServer).id == identity.id
                },
                onChange = { synchronize(it) }
            )
        }
    }

    private suspend fun saveSharedChange(
        scopeId: String,
        kind: String,
        targetId: String,
        method: String,
        path: String,
        payload: JsonObject?,
        optimisticState: JsonObject? = payload,
        ordered: Boolean = true,
        operationId: String = UUID.randomUUID().toString(),
        audioSource: String? = null,
        capturedSavedAt: Long? = null
    ): PendingOperation = synchronizationMutex.withLock {
        val serverUrl = Preferences.instance.getString(SocialPreferences.serverUrlKey, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
        val dependency = socialDao.getPendingOperations().firstOrNull {
            it.kind == "group" && it.method == "POST" && it.targetId == scopeId
        }?.operationId
        val body = if (method == "DELETE" && scopeId != identity.id) {
            val membershipId = groups.first().first { it.id == scopeId }.membershipId
            JsonObject(mapOf("membership_id" to JsonPrimitive(membershipId)))
        } else payload
        val audioFile = audioSource?.let {
            val soundId = (requireNotNull(payload)["sound"]!!.jsonObject["sound_id"] as JsonPrimitive).content
            SharedSoundStore(context).prepareUpload(soundId, it)
        }
        val operation = synchronization.saveOperation(scopeId, kind, targetId, method, path, body, optimisticState, ordered, dependency, operationId, audioFile?.absolutePath, capturedSavedAt)
        applySharedState(identity.id)
        SocialSyncWorker.enqueue(context, expedited = true)
        operation
    }

    suspend fun createGroup(
        name: String,
        permission: AlarmPermission,
        notifyAlarmChanges: Boolean,
        notifySnoozed: Boolean,
        notifyDismissed: Boolean,
        notifyIgnored: Boolean
    ) = withContext(Dispatchers.IO) {
        val groupId = UUID.randomUUID().toString()
        val payload = json.encodeToJsonElement(GroupCreate(
            name, permission.name.lowercase(), notifyAlarmChanges, notifySnoozed,
            notifyDismissed, notifyIgnored, AlarmTimeBasis.MEMBER_LOCAL.name.lowercase(),
            ZoneId.systemDefault().id, false, groupId, UUID.randomUUID().toString()
        )).jsonObject
        saveSharedChange(groupId, "group", groupId, "POST", "/v1/groups", payload)
        Unit
    }

    suspend fun saveGroupSettings(group: SocialGroup) = withContext(Dispatchers.IO) {
        if (group.role == MemberRole.LEADER) {
            val payload = JsonObject(json.encodeToJsonElement(GroupUpdate(
                group.name, group.alarmPermission.name.lowercase(), group.notifyAlarmChanges,
                group.notifySnoozed, group.notifyDismissed, group.notifyIgnored,
                group.alarmTimeBasis.name.lowercase(), group.alarmTimeZone, group.sharedAnswers
            )).jsonObject + ("membership_id" to JsonPrimitive(group.membershipId)))
            saveSharedChange(group.id, "group", group.id, "PUT", "/v1/groups/${group.id}", payload,
                JsonObject(payload + ("id" to JsonPrimitive(group.id))))
        }
        val membership = synchronization.projectedResources().first { it.kind == "membership" &&
            json.decodeFromString<MembershipAccessDto>(it.representation).id == group.membershipId }
        val payload = JsonObject(json.encodeToJsonElement(MemberNotificationUpdate(
            group.notifyMembership, group.notifyAdministrative
        )).jsonObject + ("membership_id" to JsonPrimitive(group.membershipId)))
        saveSharedChange(membership.scopeId, "membership", group.membershipId, "PUT",
            "/v1/groups/${group.id}/notification-settings", payload,
            JsonObject(Json.parseToJsonElement(membership.representation).jsonObject + payload))
        Unit
    }

    suspend fun createInvite(groupId: String): String? = withContext(Dispatchers.IO) {
        val group = groups.first().first { it.id == groupId }
        val inviteId = UUID.randomUUID().toString()
        val existing = socialDao.getPendingOperations().firstOrNull { it.kind == "invitation" && it.scopeId == groupId }
        val operation = existing ?: saveSharedChange(groupId, "invitation", inviteId, "POST", "/v1/groups/$groupId/invites",
            JsonObject(mapOf("id" to JsonPrimitive(inviteId), "membership_id" to JsonPrimitive(group.membershipId))),
            optimisticState = null, ordered = false)
        try {
            synchronize()
        } catch (_: java.io.IOException) {
            return@withContext null
        }
        socialDao.getOperation(operation.operationId)?.response?.let { json.decodeFromString<InviteResponse>(it).url }
    }

    suspend fun joinGroup(invitation: String) = withContext(Dispatchers.IO) {
        val link = SocialLink.parse(invitation)
        require(link?.destination == "join" || !invitation.contains(":") && !invitation.contains("/")) {
            "Not a Jay invitation link"
        }
        val parameters = link?.parameters.orEmpty()
        val serverUrl = parameters["server"] ?: Preferences.instance.getString(
            SocialPreferences.serverUrlKey,
            DEFAULT_SERVER_URL
        ) ?: DEFAULT_SERVER_URL
        val token = if (link == null) invitation.trim() else requireNotNull(parameters["token"]) {
            "The invitation link is incomplete"
        }
        val configuredServer = Preferences.instance.getString(
            SocialPreferences.serverUrlKey,
            DEFAULT_SERVER_URL
        ) ?: DEFAULT_SERVER_URL
        require(serverUrl.trimEnd('/') == configuredServer.trimEnd('/')) {
            "This invitation belongs to $serverUrl. Change your Jay server in settings first."
        }
        saveSharedChange("identity", "join", token, "POST", "/v1/groups/join",
            json.encodeToJsonElement(InviteJoin(token)).jsonObject, optimisticState = null, ordered = false)
        Unit
    }

    suspend fun leaveGroup(groupId: String) = withContext(Dispatchers.IO) {
        saveSharedChange(groupId, "group", groupId, "DELETE", "/v1/groups/$groupId/membership", null, ordered = false)
        Unit
    }

    suspend fun deleteGroup(groupId: String) = withContext(Dispatchers.IO) {
        saveSharedChange(groupId, "group", groupId, "DELETE", "/v1/groups/$groupId", null, ordered = false)
        Unit
    }

    suspend fun updateMember(groupId: String, deviceId: String, role: MemberRole) = withContext(Dispatchers.IO) {
        val group = groups.first().first { it.id == groupId }
        val resource = synchronization.projectedResources().first { it.scopeId == groupId && it.kind == "member" &&
            json.decodeFromString<SocialMemberDto>(it.representation).deviceId == deviceId }
        val payload = JsonObject(mapOf("role" to JsonPrimitive(role.name.lowercase()), "membership_id" to JsonPrimitive(group.membershipId)))
        saveSharedChange(groupId, "member", resource.key, "PATCH", "/v1/groups/$groupId/members/$deviceId", payload,
            JsonObject(Json.parseToJsonElement(resource.representation).jsonObject + payload))
        Unit
    }

    suspend fun getGroupActivity(groupId: String, before: String? = null): SocialActivityPage =
        withContext(Dispatchers.IO) {
            val serverUrl = Preferences.instance.getString(
                SocialPreferences.serverUrlKey,
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

    suspend fun getAlarmActivity(alarmId: String, before: String? = null): SocialActivityPage =
        withContext(Dispatchers.IO) {
            val serverUrl = Preferences.instance.getString(
                SocialPreferences.serverUrlKey,
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
        val resource = synchronization.projectedResources().first { it.scopeId == groupId && it.kind == "member" &&
            json.decodeFromString<SocialMemberDto>(it.representation).deviceId == deviceId }
        saveSharedChange(groupId, "member", resource.key, "DELETE", "/v1/groups/$groupId/members/$deviceId", null, ordered = false)
        Unit
    }

    suspend fun createSharedAlarm(
        groupId: String,
        alarm: Alarm,
        onProgress: (SharedSoundProgress) -> Unit = {}
    ): Long = withContext(Dispatchers.IO) {
        val group = groups.first().first { it.id == groupId }
        val timeZone = if (group.alarmTimeBasis == AlarmTimeBasis.GROUP_TIME_ZONE) ZoneId.of(group.alarmTimeZone) else ZoneId.systemDefault()
        alarmUseCase.prepareForScheduling(alarm, timeZone)
        val soundMode = when {
            !alarm.soundEnabled -> SharedSoundMode.OFF
            alarm.soundUri != null && canUploadSharedSounds -> SharedSoundMode.SHARED
            else -> SharedSoundMode.MEMBER_DEFAULT
        }
        val soundId = if (soundMode == SharedSoundMode.SHARED) UUID.randomUUID().toString() else null
        val alarmId = UUID.randomUUID().toString()
        val payload = json.encodeToJsonElement(SharedAlarmRequest(
            groupId = groupId, time = alarm.time, label = alarm.label, labelColor = alarm.labelColor, enabled = alarm.enabled,
            days = alarm.days, vibrate = alarm.vibrate, startDate = LocalDate.ofEpochDay(alarm.startDate).toString(),
            repeatInterval = alarm.repeatInterval, repeatUnit = alarm.repeatUnit.name, repeatAnchor = alarm.repeatAnchor.name,
            repeatDuration = alarm.repeatDuration, repeatDurationUnit = alarm.repeatDurationUnit.name,
            endDate = alarm.endDate?.let { LocalDate.ofEpochDay(it).toString() }, endOccurrences = alarm.endOccurrences,
            advanced = alarm.advanced, snoozeEnabled = alarm.snoozeEnabled, snoozeMinutes = alarm.snoozeMinutes,
            vibrationPattern = alarm.vibrationPattern, vibrationPatternName = alarm.vibrationPatternName,
            sound = SharedSoundSelection(soundMode.name.lowercase(), soundId, alarm.soundName ?: "Shared sound"),
            membershipId = group.membershipId, id = alarmId
        )).jsonObject
        val optimistic = JsonObject(payload + mapOf(
            "sound_title" to JsonPrimitive(alarm.soundName ?: "Shared sound"),
            "revision" to JsonPrimitive(1), "sound_mode" to JsonPrimitive(soundMode.name.lowercase()),
            "sound_id" to (soundId?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        ))
        saveSharedChange(groupId, "alarm", alarmId, "POST", "/v1/alarms", payload, optimistic, audioSource = alarm.soundUri.takeIf { soundId != null })
        requireNotNull(socialDao.getAlarmLinkByRemoteId(alarmId)).localAlarmId
    }

    suspend fun updateAlarm(alarm: Alarm, onProgress: (SharedSoundProgress) -> Unit = {}) = withContext(Dispatchers.IO) {
        val link = socialDao.getAlarmLinkByLocalId(alarm.id)
        if (link == null) {
            alarmUseCase.updateAlarm(alarm)
        } else {
            val group = groups.first().first { it.id == link.groupId }
            alarmUseCase.prepareForScheduling(alarm, link.timeZone?.let(ZoneId::of) ?: ZoneId.systemDefault())
            val local = alarmRepository.getAlarmById(alarm.id)
            val keepSound = alarm.soundEnabled && link.soundMode == SharedSoundMode.SHARED &&
                alarm.soundName == link.soundTitle && (alarm.soundUri == null || alarm.soundUri == local?.soundUri)
            val soundMode = when {
                !alarm.soundEnabled -> SharedSoundMode.OFF
                keepSound || alarm.soundUri != null && canUploadSharedSounds -> SharedSoundMode.SHARED
                else -> SharedSoundMode.MEMBER_DEFAULT
            }
            val soundId = if (keepSound) link.soundId else if (soundMode == SharedSoundMode.SHARED) UUID.randomUUID().toString() else null
            val payload = JsonObject(json.encodeToJsonElement(SharedAlarmRequest(
                time = alarm.time, label = alarm.label, labelColor = alarm.labelColor, enabled = alarm.enabled, days = alarm.days,
                vibrate = alarm.vibrate, startDate = LocalDate.ofEpochDay(alarm.startDate).toString(),
                repeatInterval = alarm.repeatInterval, repeatUnit = alarm.repeatUnit.name, repeatAnchor = alarm.repeatAnchor.name,
                repeatDuration = alarm.repeatDuration, repeatDurationUnit = alarm.repeatDurationUnit.name,
                endDate = alarm.endDate?.let { LocalDate.ofEpochDay(it).toString() }, endOccurrences = alarm.endOccurrences,
                advanced = alarm.advanced, snoozeEnabled = alarm.snoozeEnabled, snoozeMinutes = alarm.snoozeMinutes,
                vibrationPattern = alarm.vibrationPattern, vibrationPatternName = alarm.vibrationPatternName,
                sound = SharedSoundSelection(soundMode.name.lowercase(), soundId, alarm.soundName ?: "Shared sound"),
                membershipId = group.membershipId
            )).jsonObject.filterKeys { it !in setOf("id", "group_id") })
            val optimistic = JsonObject(payload + mapOf(
                "id" to JsonPrimitive(link.remoteAlarmId), "group_id" to JsonPrimitive(link.groupId),
                "sound_title" to JsonPrimitive(alarm.soundName ?: "Shared sound"),
                "revision" to JsonPrimitive(link.revision + 1), "sound_mode" to JsonPrimitive(soundMode.name.lowercase()),
                "sound_id" to (soundId?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
            ))
            saveSharedChange(link.groupId, "alarm", link.remoteAlarmId, "PUT", "/v1/alarms/${link.remoteAlarmId}", payload, optimistic, audioSource = alarm.soundUri.takeIf { soundId != null && !keepSound })
        }
    }

    suspend fun deleteAlarm(alarm: Alarm) = withContext(Dispatchers.IO) {
        val link = socialDao.getAlarmLinkByLocalId(alarm.id)
        if (link == null) alarmUseCase.deleteAlarm(alarm)
        else saveSharedChange(link.groupId, "alarm", link.remoteAlarmId, "DELETE", "/v1/alarms/${link.remoteAlarmId}", null, ordered = false)
        Unit
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
                SocialPreferences.serverUrlKey,
                DEFAULT_SERVER_URL
            ) ?: DEFAULT_SERVER_URL
            val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
            val group = groups.first().firstOrNull { it.id == link.groupId } ?: return@withContext
            synchronization.saveOperation(link.groupId, "outcome", eventId, "POST", "/v1/alarms/${link.remoteAlarmId}/activity",
                json.encodeToJsonElement(AlarmActivityRequest(eventId, link.revision, kind.name.lowercase(), occurredAt, occurrenceId, reason, group.membershipId)).jsonObject,
                null, ordered = false, operationId = eventId)
            SocialSyncWorker.enqueue(context, expedited = true)
        }

    /**
     * Hands the group timers the server still holds to the timer service, which materializes the
     * ones this device has not answered yet and drops the ones the group is done with. A timer
     * this device dismissed is suppressed for that expiry, so another run can return while
     * answering a group timer stays everyone's own.
     */
    private suspend fun applySharedTimers(
        timers: List<com.bnyro.clock.social.domain.SharedTimerDto>,
        groups: List<SocialGroup>
    ) {
        val now = System.currentTimeMillis()
        socialDao.clearDismissedTimers(now)
        val suppressedTimers = socialDao.getDismissedTimers().associateBy { it.timerId }
        val activeTimerIds = mutableListOf<String>()
        timers.forEach { remote ->
            val expiresAt = runCatching {
                java.time.OffsetDateTime.parse(remote.expiresAt).toInstant().toEpochMilli()
            }.getOrNull() ?: return@forEach
            if (expiresAt < now - SHARED_TIMER_LINGER_MILLIS) return@forEach
            val dismissed = suppressedTimers[remote.id]
            if (dismissed?.timerExpiresAt == expiresAt ||
                (dismissed?.timerExpiresAt == 0L && expiresAt <= now)
            ) return@forEach
            val group = groups.firstOrNull { it.id == remote.groupId } ?: return@forEach
            val soundMode = SharedSoundMode.valueOf(remote.soundMode.uppercase())
            val soundFile = remote.soundId?.takeIf { soundMode == SharedSoundMode.SHARED }
                ?.let { SharedSoundStore(context).cached(it) }
            activeTimerIds += remote.id
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                Intent(context, TimerService::class.java)
                    .setAction(TimerService.SYNC_SHARED_TIMER_ACTION)
                    .putExtra(TimerService.SHARED_TIMER_ID_EXTRA_KEY, remote.id)
                    .putExtra(TimerService.SHARED_TIMER_GROUP_NAME_EXTRA_KEY, group.name)
                    .putExtra(TimerService.SHARED_TIMER_LABEL_EXTRA_KEY, remote.label)
                    .putExtra(TimerService.SHARED_TIMER_LABEL_COLOR_EXTRA_KEY, remote.labelColor)
                    .putExtra(TimerService.SHARED_TIMER_DURATION_EXTRA_KEY, remote.durationSeconds)
                    .putExtra(TimerService.SHARED_TIMER_INCREMENT_EXTRA_KEY, remote.incrementSeconds)
                    .putExtra(TimerService.SHARED_TIMER_EXPIRES_EXTRA_KEY, expiresAt)
                    .putExtra(TimerService.SHARED_TIMER_CAN_EDIT_EXTRA_KEY, group.canEditAlarms)
                    .putExtra(
                        TimerService.SHARED_TIMER_ANSWER_AS_ONE_EXTRA_KEY,
                        group.sharedAnswers
                    )
                    .putExtra(
                        TimerService.SHARED_TIMER_SOUND_ENABLED_EXTRA_KEY,
                        soundMode != SharedSoundMode.OFF
                    )
                    .putExtra(
                        TimerService.SHARED_TIMER_SOUND_NAME_EXTRA_KEY,
                        remote.soundTitle
                    )
                    .putExtra(
                        TimerService.SHARED_TIMER_SOUND_URI_EXTRA_KEY,
                        soundFile?.takeIf { System.currentTimeMillis() < expiresAt }
                            ?.toURI()?.toString()
                    )
                    .putExtra(TimerService.SHARED_TIMER_VIBRATE_EXTRA_KEY, remote.vibrate)
                    .putExtra(
                        TimerService.SHARED_TIMER_VIBRATION_PATTERN_EXTRA_KEY,
                        remote.vibrationPattern.toIntArray()
                    )
                    .putExtra(
                        TimerService.SHARED_TIMER_VIBRATION_PATTERN_NAME_EXTRA_KEY,
                        remote.vibrationPatternName
                    )
            )
        }
        context.sendBroadcast(
            TimerService.updateStateIntent(TimerService.PRUNE_SHARED_TIMERS_ACTION, 0)
                .setPackage(context.packageName)
                .putExtra(TimerService.ACTIVE_SHARED_TIMER_IDS_EXTRA_KEY, ArrayList(activeTimerIds))
        )
    }

    suspend fun startSharedTimer(
        groupId: String,
        label: String?,
        settings: TimerSettings,
        onProgress: (SharedSoundProgress) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val group = groups.first().first { it.id == groupId }
        val soundMode = when {
            !settings.soundEnabled -> SharedSoundMode.OFF
            settings.soundUri != null && canUploadSharedSounds -> SharedSoundMode.SHARED
            else -> SharedSoundMode.MEMBER_DEFAULT
        }
        val soundId = if (soundMode == SharedSoundMode.SHARED) UUID.randomUUID().toString() else null
        val timerId = UUID.randomUUID().toString()
        val payload = json.encodeToJsonElement(SharedTimerRequest(
            label = label, labelColor = settings.labelColor, durationSeconds = settings.seconds,
            incrementSeconds = settings.incrementSeconds ?: Preferences.instance.getInt(Preferences.timerIncrementSecondsKey, 60),
            vibrate = settings.vibrate, vibrationPattern = settings.vibrationPattern,
            vibrationPatternName = settings.vibrationPatternName,
            sound = SharedSoundSelection(soundMode.name.lowercase(), soundId, settings.soundName ?: "Shared sound"),
            expiresAt = Instant.ofEpochMilli(System.currentTimeMillis() + settings.seconds * 1000L).toString(),
            membershipId = group.membershipId, id = timerId
        )).jsonObject
        val optimistic = JsonObject(payload + mapOf(
            "sound_title" to JsonPrimitive(settings.soundName ?: "Shared sound"),
            "group_id" to JsonPrimitive(groupId), "sound_mode" to JsonPrimitive(soundMode.name.lowercase()),
            "sound_id" to (soundId?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        ))
        saveSharedChange(groupId, "timer", timerId, "POST", "/v1/groups/$groupId/timers", payload, optimistic, audioSource = settings.soundUri.takeIf { soundId != null })
        Unit
    }

    suspend fun adjustSharedTimer(timerId: String, expiresAt: Long, operationId: String, savedAt: Long) = withContext(Dispatchers.IO) {
        if (socialDao.getOperation(operationId) != null) return@withContext
        val resource = synchronization.projectedResources().firstOrNull { it.kind == "timer" && it.key == timerId } ?: return@withContext
        val timer = json.decodeFromString<SharedTimerDto>(resource.representation)
        val group = groups.first().first { it.id == timer.groupId }
        val payload = JsonObject(json.encodeToJsonElement(SharedTimerRequest(
            label = timer.label, labelColor = timer.labelColor, durationSeconds = timer.durationSeconds, incrementSeconds = timer.incrementSeconds,
            vibrate = timer.vibrate, vibrationPattern = timer.vibrationPattern, vibrationPatternName = timer.vibrationPatternName,
            sound = SharedSoundSelection(timer.soundMode, timer.soundId), expiresAt = Instant.ofEpochMilli(expiresAt).toString(),
            membershipId = group.membershipId
        )).jsonObject.filterKeys { it != "id" })
        saveSharedChange(group.id, "timer", timerId, "PUT", "/v1/timers/$timerId", payload,
            JsonObject(Json.parseToJsonElement(resource.representation).jsonObject + payload), operationId = operationId, capturedSavedAt = savedAt)
        Unit
    }

    suspend fun cancelSharedTimer(timerId: String, operationId: String = UUID.randomUUID().toString()) = withContext(Dispatchers.IO) {
        if (socialDao.getOperation(operationId) != null) return@withContext
        val resource = synchronization.projectedResources().firstOrNull { it.kind == "timer" && it.key == timerId } ?: return@withContext
        saveSharedChange(resource.scopeId, "timer", timerId, "DELETE", "/v1/timers/$timerId", null, ordered = false, operationId = operationId)
        Unit
    }

    suspend fun suppressSharedTimer(timerId: String, timerExpiresAt: Long) = withContext(Dispatchers.IO) {
        socialDao.putDismissedTimer(
            com.bnyro.clock.social.domain.DismissedSharedTimer(
                timerId,
                System.currentTimeMillis() + SUPPRESSED_TIMER_LIFETIME_MILLIS,
                timerExpiresAt
            )
        )
    }

    suspend fun renameDevice(name: String) = withContext(Dispatchers.IO) {
        val serverUrl = Preferences.instance.getString(SocialPreferences.serverUrlKey, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
        Preferences.edit { putString(SocialPreferences.deviceNameKey, name) }
        saveSharedChange(socialDao.getScope("/v1/sync")?.scopeId ?: identity.id, "identity", identity.id, "PATCH", "/v1/identity",
            json.encodeToJsonElement(DeviceUpdate(name)).jsonObject, optimisticState = null)
        Unit
    }

    suspend fun registerPushToken(token: String) = withContext(Dispatchers.IO) {
        val serverUrl = Preferences.instance.getString(SocialPreferences.serverUrlKey, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
        synchronization.saveOperation(identity.id, "push", identity.id, "PUT", "/v1/identity/push-token",
            json.encodeToJsonElement(PushTokenUpdate(token)).jsonObject, null, ordered = false)
        SocialSyncWorker.enqueue(context)
    }

    suspend fun refreshPlayEntitlement() = synchronizationMutex.withLock {
        withContext(Dispatchers.IO) {
            val serverUrl = Preferences.instance.getString(
                SocialPreferences.serverUrlKey,
                DEFAULT_SERVER_URL
            ) ?: DEFAULT_SERVER_URL
            val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
            val api = SocialApi(serverUrl, identity)
            api.register()
            val capabilities = api.deviceCapabilities()
            applyDeviceCapabilities(capabilities, serverUrl, identity.id)
            if (!capabilities.requiresPlayEntitlement) return@withContext
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
            applyDeviceCapabilities(api.updatePlayEntitlement(integrityToken), serverUrl, identity.id)
        }
    }

    suspend fun exportIdentity(): String = withContext(Dispatchers.IO) {
        val serverUrl = Preferences.instance.getString(
            SocialPreferences.serverUrlKey,
            DEFAULT_SERVER_URL
        ) ?: DEFAULT_SERVER_URL
        val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
        "${SocialLink.BASE_URL}/profile#name=${java.net.URLEncoder.encode(identity.name, Charsets.UTF_8.name())}" +
                "&key=${java.net.URLEncoder.encode(identity.secret, Charsets.UTF_8.name())}"
    }

    /**
     * Adopts an exported profile as this device's identity: everything the identity here owns
     * goes away, and the device continues as the imported member with its groups, shared
     * alarms, and answers.
     */
    suspend fun importIdentity(profile: String): SocialSyncResult = withContext(Dispatchers.IO) {
        val link = SocialLink.parse(profile)
        require(link?.destination == "profile") { "Not a Jay profile link" }
        val parameters = link.parameters
        val name = parameters["name"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("The profile link is incomplete")
        val secret = parameters["key"]
            ?: throw IllegalArgumentException("The profile link is incomplete")
        require(Base64.decode(secret, Base64.NO_WRAP or Base64.URL_SAFE).size == 32) {
            "The profile link is incomplete"
        }
        synchronizationMutex.withLock {
            discardLocalIdentityState()
            context.getSharedPreferences("jay_identity", Context.MODE_PRIVATE).edit {
                putString(SocialPreferences.deviceSecretKey, secret)
            }
            Preferences.edit { putString(SocialPreferences.deviceNameKey, name) }
        }
        if (FirebaseApp.getApps(context).isNotEmpty()) {
            runCatching { registerPushToken(Tasks.await(FirebaseMessaging.getInstance().token)) }
        }
        synchronize()
    }

    suspend fun resetIdentity(): SocialSyncResult = withContext(Dispatchers.IO) {
        val serverUrl = Preferences.instance.getString(
            SocialPreferences.serverUrlKey,
            DEFAULT_SERVER_URL
        ) ?: DEFAULT_SERVER_URL
        val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
        synchronizationMutex.withLock {
            try {
                val operation = synchronization.saveOperation(identity.id, "identity", identity.id, "DELETE", "/v1/identity", null, null, ordered = false)
                SocialApi(serverUrl, identity).executeOperation(operation)
            } catch (removed: SocialApiException) {
                if (removed.status != 401) throw removed
            }
            discardLocalIdentity()
        }
        synchronize()
    }

    /**
     * Drops every local trace of the current identity and rotates its secret, so the next
     * registration is a brand-new device the server has never seen.
     */
    private suspend fun discardLocalIdentity() {
        discardLocalIdentityState()
        Preferences.edit { remove(SocialPreferences.deviceNameKey) }
        context.getSharedPreferences("jay_identity", Context.MODE_PRIVATE).edit { clear() }
        if (FirebaseApp.getApps(context).isNotEmpty()) {
            runCatching { registerPushToken(Tasks.await(FirebaseMessaging.getInstance().token)) }
        }
    }

    /**
     * Clears everything the current identity owns locally, leaving the stored secret behind
     * for whoever adopts this device next: a fresh identity or an imported profile.
     */
    private suspend fun discardLocalIdentityState() {
        socialDao.getAlarmLinks().forEach { deleteSharedAlarmLink(it) }
        socialDatabase.withTransaction {
            socialDao.clearMembers()
            socialDao.clearGroups()
            socialDao.clearScopes()
            socialDao.clearResources()
            socialDao.clearAllStaging()
            socialDao.clearOperations()
            socialDao.clearPendingActivity()
            socialDao.clearClock()
            socialDao.clearDismissedTimers(Long.MAX_VALUE)
        }
        Preferences.edit {
            remove(SocialPreferences.pendingInvitationKey)
            remove(SocialPreferences.pendingProfileKey)
            remove(SocialPreferences.capabilitiesKey)
            remove(SocialPreferences.capabilitiesServerKey)
            remove(SocialPreferences.capabilitiesDeviceKey)
        }
        Preferences.instance.all.keys.filter {
            it.startsWith(SocialPreferences.alarmOccurrencePrefix) ||
                it.startsWith(SocialPreferences.alarmTimeZonePrefix)
        }.forEach { key ->
            Preferences.edit { remove(key) }
        }
        context.getSharedPreferences(
            "jay_social_notification_accumulation",
            Context.MODE_PRIVATE
        ).edit { clear() }
        SharedSoundStore(context).prune(emptySet())
    }

    private suspend fun deleteSharedAlarmLink(link: SharedAlarmLink) {
        alarmRepository.getAlarmById(link.localAlarmId)?.let {
            context.sendBroadcast(
                Intent(AlarmService.CANCEL_SHARED_ALARM_INTENT_ACTION)
                    .setPackage(context.packageName)
                    .putExtra(AlarmHelper.EXTRA_ID, it.id)
            )
            alarmUseCase.deleteAlarm(it)
        }
        socialDao.deleteAlarmLink(link.remoteAlarmId)
        SocialAlarmSchedule.setTimeZone(link.localAlarmId, null)
        WorkManager.getInstance(context).cancelUniqueWork(
            "jay_ignored_alarm_${link.localAlarmId}"
        )
    }

    suspend fun changeServer(serverUrl: String) = withContext(Dispatchers.IO) {
        URI(serverUrl).toURL()
        discardLocalIdentityState()
        Preferences.edit { putString(SocialPreferences.serverUrlKey, serverUrl.trimEnd('/')) }
    }

    private suspend fun scheduleIgnoredOutcome(
        alarm: Alarm,
        remoteAlarmId: String,
        revision: Int
    ) {
        val triggerAt = AlarmHelper.getAlarmTime(alarm)
        if (!alarm.enabled || triggerAt == null) {
            WorkManager.getInstance(context).cancelUniqueWork("jay_ignored_alarm_${alarm.id}")
            return
        }
        val occurrenceId = triggerAt.toString()
        SocialIgnoredAlarmWorker.schedule(
            context,
            alarm.id,
            triggerAt,
            occurrenceId
        )
        val serverUrl = Preferences.instance.getString(
            SocialPreferences.serverUrlKey,
            DEFAULT_SERVER_URL
        ) ?: DEFAULT_SERVER_URL
        val identity = DeviceIdentityStore.loadOrCreate(context, serverUrl)
        val link = socialDao.getAlarmLinkByRemoteId(remoteAlarmId) ?: return
        val group = groups.first().firstOrNull { it.id == link.groupId } ?: return
        val payload = json.encodeToJsonElement(AlarmOccurrenceSchedule(
            revision, occurrenceId, Instant.ofEpochMilli(triggerAt).toString(),
            Instant.ofEpochMilli(triggerAt + Preferences.instance.getInt(Preferences.alarmTimeoutMinutesKey, AlarmService.ALARM_TIMEOUT_MINUTES) * 60_000L).toString(),
            Instant.ofEpochMilli(triggerAt).atZone(SocialAlarmSchedule.timeZone(alarm.id)).toLocalDate().toString(),
            group.membershipId
        )).jsonObject
        val operationId = UUID.nameUUIDFromBytes("occurrence:${identity.id}:$remoteAlarmId:$revision:$payload".toByteArray()).toString()
        synchronization.saveOperation(link.groupId, "occurrence", occurrenceId, "PUT", "/v1/alarms/$remoteAlarmId/occurrence",
            payload, null, ordered = false, operationId = operationId)
        SocialSyncWorker.enqueue(context)
    }

    companion object {
        const val DEFAULT_SERVER_URL = "https://jay.poppybit.com"

        private val RESOLVED_OCCURRENCE_STATUSES = setOf("dismissed", "ignored", "snoozed")
        private const val SHARED_TIMER_LINGER_MILLIS = 15 * 60_000L
        private const val SUPPRESSED_TIMER_LIFETIME_MILLIS = 30 * 60_000L
    }
}
