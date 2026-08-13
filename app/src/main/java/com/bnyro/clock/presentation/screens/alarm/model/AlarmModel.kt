package com.bnyro.clock.presentation.screens.alarm.model

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bnyro.clock.App
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.model.AlarmFilters
import com.bnyro.clock.domain.model.AlarmSortOrder
import com.bnyro.clock.domain.repository.AlarmRepository
import com.bnyro.clock.domain.usecase.CreateUpdateDeleteAlarmUseCase
import com.bnyro.clock.social.data.SocialActivityWorker
import com.bnyro.clock.social.domain.AlarmActivityKind
import com.bnyro.clock.social.domain.PERSONAL_ALARM_SOURCE_ID
import com.bnyro.clock.social.domain.SocialChange
import com.bnyro.clock.social.domain.canEditAlarms
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Collections
import kotlinx.coroutines.flow.map

class AlarmModel(application: Application) : AndroidViewModel(application) {
    private val alarmRepository: AlarmRepository = (application as App).container.alarmRepository
    private val createUpdateDeleteAlarmUseCase =
        CreateUpdateDeleteAlarmUseCase(application.applicationContext, alarmRepository)
    private val socialRepository = (application as App).container.socialRepository

    var showFilter by mutableStateOf(false)
    var showSortOrder by mutableStateOf(false)
    var alarmActivity by mutableStateOf<List<SocialChange>>(emptyList())
        private set
    var alarmActivityNextBefore by mutableStateOf<Long?>(null)
        private set
    var selectedActivityAlarmId by mutableStateOf<String?>(null)
    val filters = MutableStateFlow(AlarmFilters())
    val alarmSourceIds = MutableStateFlow<Set<String>?>(null)
    private val sortOrder = MutableStateFlow(AlarmSortOrder.HOUR_OF_DAY)

    val alarms: StateFlow<List<Alarm>> =
        combine(
            alarmRepository.getAlarmsStream(),
            filters,
            sortOrder,
            socialRepository.alarmGroupNames,
            alarmSourceIds
        ) { items, filter, sortOrder, alarmGroups, sourceIds ->
            val alarmGroupsByAlarmId = alarmGroups.associateBy { it.localAlarmId }
            val filtered = items.filter { alarm ->
                val sourceId = alarmGroupsByAlarmId[alarm.id]?.groupId
                    ?: PERSONAL_ALARM_SOURCE_ID
                (filter.startTime <= alarm.time && alarm.time <= filter.endTime)
                        && !Collections.disjoint(filter.weekDays, alarm.days)
                        && (alarm.label?.lowercase()?.contains(filter.label.lowercase())
                    ?: true) && (alarm.formattedTime.lowercase()
                    .contains(filter.label.lowercase()))
                        && (sourceIds == null || sourceId in sourceIds)

            }

            when (sortOrder) {
                AlarmSortOrder.LABEL -> filtered.sortedBy { it.label }
                AlarmSortOrder.HOUR_OF_DAY -> filtered.sortedBy { it.time }
                AlarmSortOrder.WEEKDAY -> filtered.sortedBy { it.days.firstOrNull() }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = listOf()
        )
    val groups = socialRepository.groups.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )
    val alarmGroupNames = socialRepository.alarmGroupNames.map { names ->
        names.associate { it.localAlarmId to it.groupName }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyMap()
    )
    val remoteAlarmIds = socialRepository.alarmGroupNames.map { names ->
        names.associate { it.localAlarmId to it.remoteAlarmId }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyMap()
    )
    val alarmEditability = combine(
        socialRepository.alarmGroupNames,
        socialRepository.groups
    ) { alarmGroups, groups ->
        alarmGroups.associate { alarmGroup ->
            alarmGroup.localAlarmId to
                    (groups.firstOrNull { it.id == alarmGroup.groupId }?.canEditAlarms == true)
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyMap()
    )
    fun updateAlarm(alarm: Alarm) {
        viewModelScope.launch {
            runCatching { socialRepository.updateAlarm(alarm) }
                .onFailure { socialRepository.synchronize() }
        }
    }

    fun loadAlarmActivity(alarmId: String, more: Boolean = false) {
        viewModelScope.launch {
            runCatching {
                socialRepository.getAlarmActivity(
                    alarmId,
                    if (more) alarmActivityNextBefore else null
                )
            }.onSuccess {
                selectedActivityAlarmId = alarmId
                alarmActivity = if (more) alarmActivity + it.items else it.items
                alarmActivityNextBefore = it.nextBefore
            }
        }
    }

    fun dismissUpcomingAlarm(alarm: Alarm) {
        SocialActivityWorker.enqueue(
            getApplication(),
            alarm.id,
            AlarmActivityKind.DISMISSED
        )
        viewModelScope.launch {
            createUpdateDeleteAlarmUseCase.dismissUpcomingAlarm(alarm)
        }
    }

    fun copyAlarm(alarm: Alarm) {
        viewModelScope.launch {
            createUpdateDeleteAlarmUseCase.createAlarm(alarm.copy(id = 0L))
        }
    }



    fun deleteAlarm(alarm: Alarm) {
        viewModelScope.launch {
            runCatching { socialRepository.deleteAlarm(alarm) }
                .onFailure { socialRepository.synchronize() }
        }
    }

    fun updateLabelFilter(label: String) {
        filters.update { it.copy(label = label) }
    }

    fun updateWeekDayFilter(weekDays: List<Int>) {
        filters.update { it.copy(weekDays = weekDays) }
    }

    fun updateStartTimeFilter(startTime: Long) {
        filters.update { it.copy(startTime = startTime) }
    }

    fun updateEndTimeFilter(endTime: Long) {
        filters.update { it.copy(endTime = endTime) }
    }

    fun setSortOrder(order: AlarmSortOrder) {
        sortOrder.update { order }
    }

    fun resetFilters() {
        filters.update { AlarmFilters() }
        alarmSourceIds.update { null }
    }
}
