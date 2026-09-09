package com.bnyro.clock.social.presentation

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bnyro.clock.App
import com.bnyro.clock.social.domain.AlarmPermission
import com.bnyro.clock.social.domain.MemberRole
import com.bnyro.clock.social.domain.SocialGroup
import com.bnyro.clock.social.domain.SocialMember
import com.bnyro.clock.domain.model.TimerSettings
import com.bnyro.clock.social.domain.SocialChange
import com.bnyro.clock.social.data.SocialPreferences
import com.bnyro.clock.util.Preferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SocialModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as App).container.socialRepository
    private var rejectedOperation: com.bnyro.clock.social.domain.PendingOperation? = null
    private var invitationOperation: com.bnyro.clock.social.domain.PendingOperation? = null

    val canUploadSharedSounds: Boolean
        get() = repository.canUploadSharedSounds

    val groups: StateFlow<List<SocialGroup>> = repository.groups.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )
    val members = repository.members.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var invitation by mutableStateOf<String?>(null)
        private set
    var profile by mutableStateOf<String?>(null)
        private set
    var groupActivity by mutableStateOf<List<SocialChange>>(emptyList())
        private set
    var groupActivityNextBefore by mutableStateOf<String?>(null)
        private set
    var alarmActivity by mutableStateOf<List<SocialChange>>(emptyList())
        private set
    var alarmActivityNextBefore by mutableStateOf<String?>(null)
        private set
    var activityAlarmId by mutableStateOf<String?>(null)
    var deviceId by mutableStateOf<String?>(null)
        private set
    var deviceName by mutableStateOf(
        Preferences.instance.getString(SocialPreferences.deviceNameKey, null).orEmpty()
    )
        private set
    var serverUrl by mutableStateOf(
        Preferences.instance.getString(
            SocialPreferences.serverUrlKey,
            com.bnyro.clock.social.data.SocialRepository.DEFAULT_SERVER_URL
        ) ?: com.bnyro.clock.social.data.SocialRepository.DEFAULT_SERVER_URL
    )
        private set

    init {
        synchronize()
        viewModelScope.launch {
            repository.rejectedOperations.collect { operations ->
                operations.firstOrNull()?.let { operation ->
                    rejectedOperation = operation
                    message = when (operation.rejectionCode) {
                        "superseded" -> "Your offline change was replaced by a newer saved change."
                        "clock_invalid" -> "Your change could not be synchronized because its saved time is ahead of the server. Review it and save again."
                        "not_found", "membership_changed", "editor_required", "leader_required" -> "Your change could not be applied because the item or your group access changed."
                        else -> "Your change could not be applied. Current group information has been restored."
                    }
                }
            }
        }
        viewModelScope.launch {
            repository.readyInvitations.collect { operations ->
                operations.firstOrNull()?.let { operation ->
                    invitationOperation = operation
                    invitation = kotlinx.serialization.json.Json.decodeFromString<com.bnyro.clock.social.domain.InviteResponse>(requireNotNull(operation.response)).url
                }
            }
        }
    }

    fun synchronize() {
        viewModelScope.launch {
            busy = true
            runCatching { repository.synchronize() }
                .onSuccess { result ->
                    deviceId = result.deviceId
                    deviceName = Preferences.instance.getString(
                        SocialPreferences.deviceNameKey,
                        deviceName
                    ) ?: deviceName
                }
                .onFailure { message = it.message ?: "Unable to synchronize" }
            busy = false
        }
    }

    fun startGroupTimer(groupId: String, label: String?, settings: TimerSettings) {
        viewModelScope.launch {
            busy = true
            runCatching {
                repository.startSharedTimer(groupId, label, settings)
            }.onFailure { message = it.message ?: "Unable to start group timer" }
            busy = false
        }
    }

    fun createGroup(
        name: String,
        permission: AlarmPermission,
        notifyAlarmChanges: Boolean,
        notifySnoozed: Boolean,
        notifyDismissed: Boolean,
        notifyIgnored: Boolean
    ) {
        viewModelScope.launch {
            busy = true
            runCatching {
                repository.createGroup(
                    name,
                    permission,
                    notifyAlarmChanges,
                    notifySnoozed,
                    notifyDismissed,
                    notifyIgnored
                )
            }.onFailure { message = it.message ?: "Unable to create group" }
            busy = false
        }
    }

    fun saveGroupSettings(group: SocialGroup) {
        viewModelScope.launch {
            busy = true
            runCatching { repository.saveGroupSettings(group) }
                .onFailure { message = it.message ?: "Unable to update group" }
            busy = false
        }
    }

    fun createInvite(groupId: String) {
        viewModelScope.launch {
            busy = true
            runCatching { repository.createInvite(groupId) }
                .onSuccess { if (it != null) invitation = it else message = "Invitation saved. Its link will be available after reconnecting." }
                .onFailure { message = it.message ?: "Unable to create invitation" }
            busy = false
        }
    }

    fun joinGroup(invitation: String) {
        viewModelScope.launch {
            busy = true
            runCatching { repository.joinGroup(invitation) }
                .onFailure { message = it.message ?: "Unable to join group" }
            busy = false
        }
    }

    fun leaveGroup(groupId: String) {
        viewModelScope.launch {
            busy = true
            runCatching { repository.leaveGroup(groupId) }
                .onFailure { message = it.message ?: "Unable to leave group" }
            busy = false
        }
    }

    fun deleteGroup(groupId: String) {
        viewModelScope.launch {
            busy = true
            runCatching { repository.deleteGroup(groupId) }
                .onFailure { message = it.message ?: "Unable to delete group" }
            busy = false
        }
    }

    fun updateMember(groupId: String, member: SocialMember, role: MemberRole) {
        viewModelScope.launch {
            busy = true
            runCatching { repository.updateMember(groupId, member.deviceId, role) }
                .onFailure { message = it.message ?: "Unable to update member" }
            busy = false
        }
    }

    fun loadGroupActivity(groupId: String, more: Boolean = false) {
        viewModelScope.launch {
            busy = true
            runCatching {
                repository.getGroupActivity(
                    groupId,
                    if (more) groupActivityNextBefore else null
                )
            }.onSuccess {
                groupActivity = if (more) groupActivity + it.items else it.items
                groupActivityNextBefore = it.nextBefore
            }.onFailure { message = it.message ?: "Unable to load activity" }
            busy = false
        }
    }

    fun loadAlarmActivity(alarmId: String, more: Boolean = false) {
        viewModelScope.launch {
            busy = true
            runCatching {
                repository.getAlarmActivity(
                    alarmId,
                    if (more) alarmActivityNextBefore else null
                )
            }.onSuccess {
                activityAlarmId = alarmId
                alarmActivity = if (more) alarmActivity + it.items else it.items
                alarmActivityNextBefore = it.nextBefore
            }.onFailure { message = it.message ?: "Unable to load alarm activity" }
            busy = false
        }
    }

    fun removeMember(groupId: String, member: SocialMember) {
        viewModelScope.launch {
            busy = true
            runCatching { repository.removeMember(groupId, member.deviceId) }
                .onFailure { message = it.message ?: "Unable to remove member" }
            busy = false
        }
    }

    fun renameDevice(name: String) {
        viewModelScope.launch {
            busy = true
            runCatching { repository.renameDevice(name) }
                .onSuccess { deviceName = name }
                .onFailure { message = it.message ?: "Unable to rename device" }
            busy = false
        }
    }

    fun changeServer(url: String) {
        viewModelScope.launch {
            busy = true
            runCatching {
                repository.changeServer(url)
                repository.synchronize()
            }.onSuccess { result ->
                deviceId = result.deviceId
                serverUrl = url.trimEnd('/')
            }
                .onFailure { message = it.message ?: "Unable to change server" }
            busy = false
        }
    }

    fun exportProfile() {
        viewModelScope.launch {
            busy = true
            runCatching { repository.exportIdentity() }
                .onSuccess { profile = it }
                .onFailure { message = it.message ?: "Unable to export profile" }
            busy = false
        }
    }

    fun importProfile(profileLink: String) {
        viewModelScope.launch {
            busy = true
            runCatching { repository.importIdentity(profileLink) }
                .onSuccess { result ->
                    deviceId = result.deviceId
                    deviceName = Preferences.instance.getString(
                        SocialPreferences.deviceNameKey,
                        null
                    ).orEmpty()
                }
                .onFailure { message = it.message ?: "Unable to import profile" }
            busy = false
        }
    }

    fun resetIdentity() {
        viewModelScope.launch {
            busy = true
            runCatching { repository.resetIdentity() }
                .onSuccess { result ->
                    deviceId = result.deviceId
                    deviceName = Preferences.instance.getString(
                        SocialPreferences.deviceNameKey,
                        null
                    ).orEmpty()
                }
                .onFailure { message = it.message ?: "Unable to reset identity" }
            busy = false
        }
    }

    fun consumeMessage() {
        message = null
        rejectedOperation?.let { operation ->
            rejectedOperation = null
            viewModelScope.launch { repository.acknowledgeOperation(operation) }
        }
    }

    fun consumeInvitation() {
        invitation = null
        invitationOperation?.let { operation ->
            invitationOperation = null
            viewModelScope.launch { repository.acknowledgeOperation(operation) }
        }
    }

    fun consumeProfile() {
        profile = null
    }
}
