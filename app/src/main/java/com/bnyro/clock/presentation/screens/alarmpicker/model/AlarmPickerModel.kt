package com.bnyro.clock.presentation.screens.alarmpicker.model

import android.app.Application
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bnyro.clock.App
import com.bnyro.clock.domain.model.Alarm
import com.bnyro.clock.domain.usecase.CreateUpdateDeleteAlarmUseCase
import com.bnyro.clock.navigation.NavRoutes
import com.bnyro.clock.util.TimeHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class AlarmPickerModel(application: Application, savedStateHandle: SavedStateHandle) :
    AndroidViewModel(application) {
    private val id: Long = savedStateHandle[NavRoutes.AlarmPicker.ALARM_ID] ?: 0L
    val advanced: Boolean = savedStateHandle[NavRoutes.AlarmPicker.ADVANCED] ?: false

    private val alarmRepository = (application as App).container.alarmRepository
    private val createUpdateDeleteAlarmUseCase =
        CreateUpdateDeleteAlarmUseCase(application.applicationContext, alarmRepository)
    private val socialRepository = (application as App).container.socialRepository
    val groups = socialRepository.groups.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    var busy by mutableStateOf(false)
        private set

    var createdAlarm by mutableStateOf<Alarm?>(null)
        private set

    var alarm: Alarm
    var groupId: String?

    init {
        alarm = if (id == 0L) {
            Alarm(time = TimeHelper.currentDayMillis)
        } else {
            runBlocking(Dispatchers.IO) {
                alarmRepository.getAlarmById(id)!!
            }
        }
        groupId = if (alarmId == 0L) null else runBlocking(Dispatchers.IO) {
            socialRepository.alarmGroupNames.first()
                .firstOrNull { it.localAlarmId == alarmId }
                ?.groupId
        }
    }

    fun createAlarm(alarm: Alarm, groupId: String?, onCreated: () -> Unit) {
        if (busy) return
        createdAlarm = null
        busy = true
        viewModelScope.launch {
            try {
                if (groupId == null) {
                    createUpdateDeleteAlarmUseCase.createAlarm(alarm)
                } else {
                    socialRepository.createSharedAlarm(groupId, alarm)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                busy = false
                Toast.makeText(
                    getApplication(),
                    exception.message ?: "Unable to create shared alarm",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            createdAlarm = alarm
            onCreated()
        }
    }

    fun updateAlarm(alarm: Alarm, onUpdated: () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                socialRepository.updateAlarm(alarm)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                busy = false
                socialRepository.synchronize()
                Toast.makeText(
                    getApplication(),
                    exception.message ?: "Unable to update shared alarm",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            onUpdated()
        }
    }

    fun deleteAlarm(alarm: Alarm, onDeleted: () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                socialRepository.deleteAlarm(alarm)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                busy = false
                socialRepository.synchronize()
                Toast.makeText(
                    getApplication(),
                    exception.message ?: "Unable to delete alarm",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            onDeleted()
        }
    }
}
