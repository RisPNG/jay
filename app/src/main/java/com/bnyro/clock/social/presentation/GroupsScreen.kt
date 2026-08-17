package com.bnyro.clock.social.presentation

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.bnyro.clock.R
import com.bnyro.clock.navigation.TopBarScaffold
import com.bnyro.clock.presentation.components.DialogButton
import com.bnyro.clock.presentation.components.DialogButtonStyle
import com.bnyro.clock.presentation.screens.settings.components.SettingsCategory
import com.bnyro.clock.social.domain.AlarmPermission
import com.bnyro.clock.social.domain.MemberRole
import com.bnyro.clock.social.domain.SocialGroup
import com.bnyro.clock.util.Preferences

@Composable
fun GroupsScreen(
    onClickSettings: () -> Unit,
    socialModel: SocialModel
) {
    val context = LocalContext.current
    val shareInvitationTitle = stringResource(R.string.share_group_invitation)
    val groups by socialModel.groups.collectAsState()
    val members by socialModel.members.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var selectedGroup by remember { mutableStateOf<SocialGroup?>(null) }
    var activityGroup by remember { mutableStateOf<SocialGroup?>(null) }

    LaunchedEffect(Unit) {
        Preferences.instance.getString(Preferences.jayPendingInvitationKey, null)?.let {
            Preferences.edit { remove(Preferences.jayPendingInvitationKey) }
            socialModel.joinGroup(it)
        }
    }

    LaunchedEffect(socialModel.message) {
        socialModel.message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            socialModel.consumeMessage()
        }
    }
    LaunchedEffect(socialModel.invitation) {
        socialModel.invitation?.let {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, it)
                    },
                    shareInvitationTitle
                )
            )
            socialModel.consumeInvitation()
        }
    }

    TopBarScaffold(
        title = stringResource(R.string.groups),
        onClickSettings = onClickSettings,
        fab = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, null)
            }
        },
        actions = {
            Row {
                IconButton(onClick = { showJoin = true }) {
                    Icon(Icons.Default.GroupAdd, stringResource(R.string.join_group))
                }
                IconButton(onClick = socialModel::synchronize, enabled = !socialModel.busy) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.sync))
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (socialModel.busy) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            }
            if (groups.isEmpty() && !socialModel.busy) {
                Text(stringResource(R.string.no_groups))
            }
            groups.forEach { group ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { selectedGroup = group }
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = group.name,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1
                        )
                        Text(
                            if (group.alarmPermission == AlarmPermission.EVERYONE) {
                                stringResource(R.string.everyone_can_edit_alarms)
                            } else {
                                stringResource(R.string.leaders_can_edit_alarms)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        var leadersOnly by remember { mutableStateOf(false) }
        var notifyAlarmChanges by remember { mutableStateOf(true) }
        var notifySnoozed by remember { mutableStateOf(true) }
        var notifyDismissed by remember { mutableStateOf(true) }
        var notifyIgnored by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.create_group)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.group_name)) }
                    )
                    GroupSwitch(stringResource(R.string.leaders_only), leadersOnly) {
                        leadersOnly = it
                    }
                    GroupSwitch(
                        stringResource(R.string.notify_alarm_changes),
                        notifyAlarmChanges
                    ) { notifyAlarmChanges = it }
                    GroupSwitch(stringResource(R.string.notify_snoozed), notifySnoozed) {
                        notifySnoozed = it
                    }
                    GroupSwitch(stringResource(R.string.notify_dismissed), notifyDismissed) {
                        notifyDismissed = it
                    }
                    GroupSwitch(stringResource(R.string.notify_ignored), notifyIgnored) {
                        notifyIgnored = it
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        socialModel.createGroup(
                            name.trim(),
                            if (leadersOnly) AlarmPermission.LEADERS else AlarmPermission.EVERYONE,
                            notifyAlarmChanges,
                            notifySnoozed,
                            notifyDismissed,
                            notifyIgnored
                        )
                        showCreate = false
                    },
                    enabled = name.isNotBlank()
                ) { Text(stringResource(R.string.create_group)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCreate = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (showJoin) {
        var invitation by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showJoin = false },
            title = { Text(stringResource(R.string.join_group)) },
            text = {
                OutlinedTextField(
                    value = invitation,
                    onValueChange = { invitation = it },
                    label = { Text(stringResource(R.string.invitation_link)) }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        socialModel.joinGroup(invitation.trim())
                        showJoin = false
                    },
                    enabled = invitation.isNotBlank()
                ) { Text(stringResource(R.string.join_group)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { showJoin = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    selectedGroup?.let { group ->
        var showDeleteConfirmation by remember(group.id) { mutableStateOf(false) }
        var name by remember(group.id, group.name) { mutableStateOf(group.name) }
        var permission by remember(group.id, group.alarmPermission) {
            mutableStateOf(group.alarmPermission)
        }
        var notifyAlarmChanges by remember(group.id, group.notifyAlarmChanges) {
            mutableStateOf(group.notifyAlarmChanges)
        }
        var notifySnoozed by remember(group.id, group.notifySnoozed) {
            mutableStateOf(group.notifySnoozed)
        }
        var notifyDismissed by remember(group.id, group.notifyDismissed) {
            mutableStateOf(group.notifyDismissed)
        }
        var notifyIgnored by remember(group.id, group.notifyIgnored) {
            mutableStateOf(group.notifyIgnored)
        }
        var notifyMembership by remember(group.id, group.notifyMembership) {
            mutableStateOf(group.notifyMembership)
        }
        var notifyAdministrative by remember(group.id, group.notifyAdministrative) {
            mutableStateOf(group.notifyAdministrative)
        }
        AlertDialog(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(0.9f),
            onDismissRequest = { selectedGroup = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            title = {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(group.name, Modifier.weight(1f))
                    IconButton(onClick = {
                        socialModel.loadGroupActivity(group.id)
                        activityGroup = group
                        selectedGroup = null
                    }) {
                        Icon(Icons.Rounded.History, stringResource(R.string.group_logs))
                    }
                }
            },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                ) {
                    if (group.role == MemberRole.LEADER) {
                        SettingsCategory(stringResource(R.string.group_section))
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(stringResource(R.string.group_name)) }
                        )
                        GroupSwitch(
                            stringResource(R.string.leaders_only),
                            permission == AlarmPermission.LEADERS
                        ) {
                            permission = if (it) AlarmPermission.LEADERS else AlarmPermission.EVERYONE
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        )
                        SettingsCategory(stringResource(R.string.alarm_notifications))
                        GroupSwitch(
                            stringResource(R.string.notify_alarm_changes),
                            notifyAlarmChanges
                        ) { notifyAlarmChanges = it }
                        GroupSwitch(stringResource(R.string.notify_snoozed), notifySnoozed) {
                            notifySnoozed = it
                        }
                        GroupSwitch(stringResource(R.string.notify_dismissed), notifyDismissed) {
                            notifyDismissed = it
                        }
                        GroupSwitch(stringResource(R.string.notify_ignored), notifyIgnored) {
                            notifyIgnored = it
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                    SettingsCategory(stringResource(R.string.activity_notifications))
                    GroupSwitch(
                        stringResource(R.string.notify_membership_activity),
                        notifyMembership
                    ) {
                        notifyMembership = it
                    }
                    GroupSwitch(
                        stringResource(R.string.notify_administrative_activity),
                        notifyAdministrative
                    ) {
                        notifyAdministrative = it
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    )
                    SettingsCategory(stringResource(R.string.members))
                    Button(onClick = { socialModel.createInvite(group.id) }) {
                        Icon(Icons.Default.Share, null)
                        Text(stringResource(R.string.invite_member))
                    }
                    members.filter { it.groupId == group.id }.forEach { member ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(member.name)
                                Text(
                                    if (member.role == MemberRole.LEADER) {
                                        stringResource(R.string.leader)
                                    } else {
                                        stringResource(R.string.member)
                                    }
                                )
                            }
                            if (
                                group.role == MemberRole.LEADER &&
                                member.deviceId != socialModel.deviceId
                            ) {
                                Switch(
                                    checked = member.role == MemberRole.LEADER,
                                    onCheckedChange = {
                                        socialModel.updateMember(
                                            group.id,
                                            member,
                                            if (it) MemberRole.LEADER else MemberRole.MEMBER
                                        )
                                    }
                                )
                                IconButton(onClick = { socialModel.removeMember(group.id, member) }) {
                                    Icon(Icons.Default.PersonRemove, stringResource(R.string.remove_member))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        socialModel.leaveGroup(group.id)
                        selectedGroup = null
                    }) { Text(stringResource(R.string.leave_group)) }
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            },
            confirmButton = {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (group.role == MemberRole.LEADER) {
                        DialogButton(
                            label = R.string.delete,
                            style = DialogButtonStyle.DESTRUCTIVE
                        ) {
                            showDeleteConfirmation = true
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { selectedGroup = null }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            socialModel.saveGroupSettings(
                                group.copy(
                                    name = name.trim(),
                                    alarmPermission = permission,
                                    notifyAlarmChanges = notifyAlarmChanges,
                                    notifySnoozed = notifySnoozed,
                                    notifyDismissed = notifyDismissed,
                                    notifyIgnored = notifyIgnored,
                                    notifyMembership = notifyMembership,
                                    notifyAdministrative = notifyAdministrative
                                )
                            )
                            selectedGroup = null
                        },
                        enabled = group.role != MemberRole.LEADER || name.isNotBlank()
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        )

        if (showDeleteConfirmation) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmation = false },
                title = { Text(stringResource(R.string.delete_group)) },
                text = { Text(stringResource(R.string.delete_group_confirmation)) },
                confirmButton = {
                    DialogButton(
                        label = R.string.delete,
                        style = DialogButtonStyle.DESTRUCTIVE
                    ) {
                        socialModel.deleteGroup(group.id)
                        showDeleteConfirmation = false
                        selectedGroup = null
                    }
                },
                dismissButton = {
                    DialogButton(
                        label = android.R.string.cancel,
                        style = DialogButtonStyle.SECONDARY
                    ) {
                        showDeleteConfirmation = false
                    }
                }
            )
        }
    }

    activityGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { activityGroup = null },
            title = { Text(stringResource(R.string.group_logs)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState())
                ) {
                    if (socialModel.groupActivity.isEmpty() && !socialModel.busy) {
                        Text(stringResource(R.string.no_activity))
                    }
                    socialModel.groupActivity.forEach { change ->
                        SocialLogEntry(change, change.groupLogTitle(context))
                    }
                    if (socialModel.groupActivityNextBefore != null) {
                        OutlinedButton(
                            onClick = { socialModel.loadGroupActivity(group.id, more = true) }
                        ) { Text(stringResource(R.string.load_more)) }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { activityGroup = null }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    if (socialModel.activityAlarmId != null) {
        AlertDialog(
            onDismissRequest = { socialModel.activityAlarmId = null },
            title = { Text(stringResource(R.string.alarm_logs)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState())
                ) {
                    socialModel.alarmActivity.forEach { change ->
                        SocialLogEntry(change, change.alarmLogTitle(context))
                    }
                    if (socialModel.alarmActivityNextBefore != null) {
                        OutlinedButton(onClick = {
                            socialModel.loadAlarmActivity(
                                socialModel.activityAlarmId!!,
                                more = true
                            )
                        }) { Text(stringResource(R.string.load_more)) }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { socialModel.activityAlarmId = null }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
}

@Composable
private fun GroupSwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, Modifier.weight(1f))
        Switch(checked, onCheckedChange)
    }
}
