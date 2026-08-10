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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import com.bnyro.clock.R
import com.bnyro.clock.navigation.TopBarScaffold
import com.bnyro.clock.social.domain.AlarmPermission
import com.bnyro.clock.social.domain.MemberRole
import com.bnyro.clock.social.domain.SocialGroup
import com.bnyro.clock.social.domain.AlarmActivityKind
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
    val activity by socialModel.activity.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var selectedGroup by remember { mutableStateOf<SocialGroup?>(null) }

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
                        Text(group.name)
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
        var notifySnoozed by remember { mutableStateOf(true) }
        var notifyDismissed by remember { mutableStateOf(true) }
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
                    GroupSwitch(stringResource(R.string.notify_snoozed), notifySnoozed) {
                        notifySnoozed = it
                    }
                    GroupSwitch(stringResource(R.string.notify_dismissed), notifyDismissed) {
                        notifyDismissed = it
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        socialModel.createGroup(
                            name.trim(),
                            if (leadersOnly) AlarmPermission.LEADERS else AlarmPermission.EVERYONE,
                            notifySnoozed,
                            notifyDismissed
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
        var name by remember(group.id, group.name) { mutableStateOf(group.name) }
        var permission by remember(group.id, group.alarmPermission) {
            mutableStateOf(group.alarmPermission)
        }
        var notifySnoozed by remember(group.id, group.notifySnoozed) {
            mutableStateOf(group.notifySnoozed)
        }
        var notifyDismissed by remember(group.id, group.notifyDismissed) {
            mutableStateOf(group.notifyDismissed)
        }
        AlertDialog(
            onDismissRequest = { selectedGroup = null },
            title = { Text(group.name) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (group.role == MemberRole.LEADER) {
                        OutlinedTextField(
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
                        GroupSwitch(stringResource(R.string.notify_snoozed), notifySnoozed) {
                            notifySnoozed = it
                        }
                        GroupSwitch(stringResource(R.string.notify_dismissed), notifyDismissed) {
                            notifyDismissed = it
                        }
                        Button(
                            onClick = {
                                socialModel.updateGroup(
                                    group.copy(
                                        name = name.trim(),
                                        alarmPermission = permission,
                                        notifySnoozed = notifySnoozed,
                                        notifyDismissed = notifyDismissed
                                    )
                                )
                            },
                            enabled = name.isNotBlank()
                        ) { Text(stringResource(R.string.save)) }
                    }
                    Button(onClick = { socialModel.createInvite(group.id) }) {
                        Icon(Icons.Default.Share, null)
                        Text(stringResource(R.string.invite_member))
                    }
                    Text(stringResource(R.string.members))
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
                            if (group.role == MemberRole.LEADER) {
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
                    val groupActivity = activity.filter { it.groupId == group.id }
                    if (groupActivity.isNotEmpty()) {
                        Text(stringResource(R.string.recent_activity))
                        groupActivity.take(20).forEach { item ->
                            Text(
                                stringResource(
                                    R.string.member_alarm_activity,
                                    item.deviceName,
                                    if (item.kind == AlarmActivityKind.SNOOZED) {
                                        stringResource(R.string.social_snoozed)
                                    } else {
                                        stringResource(R.string.social_dismissed)
                                    }
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        socialModel.leaveGroup(group.id)
                        selectedGroup = null
                    }) { Text(stringResource(R.string.leave_group)) }
                }
            },
            confirmButton = {
                Button(onClick = { selectedGroup = null }) {
                    Text(stringResource(android.R.string.ok))
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
