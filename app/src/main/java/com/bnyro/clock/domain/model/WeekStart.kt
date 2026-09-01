package com.bnyro.clock.domain.model

import androidx.annotation.StringRes
import com.bnyro.clock.R
import java.time.DayOfWeek

enum class WeekStart(@StringRes val resId: Int, val dayOfWeek: DayOfWeek) {
    FRIDAY(R.string.friday, DayOfWeek.FRIDAY),
    SATURDAY(R.string.saturday, DayOfWeek.SATURDAY),
    SUNDAY(R.string.sunday, DayOfWeek.SUNDAY),
    MONDAY(R.string.monday, DayOfWeek.MONDAY)
}
