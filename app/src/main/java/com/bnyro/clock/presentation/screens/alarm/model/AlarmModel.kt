package com.bnyro.clock.presentation.screens.alarm.model

import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bnyro.clock.App
import com.bnyro.clock.R
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.model.AlarmFilters
import com.bnyro.clock.domain.model.AlarmSortOrder
import com.bnyro.clock.domain.repository.AlarmRepository
import com.bnyro.clock.domain.usecase.CreateUpdateDeleteAlarmUseCase
import com.bnyro.clock.util.AlarmHelper
import com.bnyro.clock.util.TimeHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Collections
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.map

class AlarmModel(application: Application) : AndroidViewModel(application) {
    private val alarmRepository: AlarmRepository = (application as App).container.alarmRepository
    private val createUpdateDeleteAlarmUseCase =
        CreateUpdateDeleteAlarmUseCase(application.applicationContext, alarmRepository)
    private val socialRepository = (application as App).container.socialRepository

    var showFilter by mutableStateOf(false)
    var showSortOrder by mutableStateOf(false)
    val filters = MutableStateFlow(AlarmFilters())
    private val sortOrder = MutableStateFlow(AlarmSortOrder.HOUR_OF_DAY)

    val alarms: StateFlow<List<Alarm>> =
        combine(alarmRepository.getAlarmsStream(), filters, sortOrder) { items, filter, sortOrder ->
            val filtered = items.filter { alarm ->
                (filter.startTime <= alarm.time && alarm.time <= filter.endTime)
                        && !Collections.disjoint(filter.weekDays, alarm.days)
                        && (alarm.label?.lowercase()?.contains(filter.label.lowercase())
                    ?: true) && (alarm.formattedTime.lowercase()
                    .contains(filter.label.lowercase()))

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
    val alarmGroupNames = socialRepository.alarmGroupNames.map { names ->
        names.associate { it.localAlarmId to it.groupName }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyMap()
    )
    val alarmDeliveryCounts = socialRepository.alarmDeliveryCounts.map { counts ->
        counts.associateBy { it.localAlarmId }
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


    fun copyAlarm(alarm: Alarm) {
        viewModelScope.launch {
            createUpdateDeleteAlarmUseCase.createAlarm(alarm.copy(id = 0L))
        }
    }



    fun createToast(alarm: Alarm, context: Context) {
        val millisRemainingForAlarm =
            (AlarmHelper.getAlarmTime(alarm) - System.currentTimeMillis())
        val formattedDuration =
            TimeHelper.durationToFormatted(context, millisRemainingForAlarm.milliseconds)
        Toast.makeText(
            context,
            context.resources.getString(R.string.alarm_will_play, formattedDuration),
            Toast.LENGTH_SHORT
        ).show()
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
    }
}
