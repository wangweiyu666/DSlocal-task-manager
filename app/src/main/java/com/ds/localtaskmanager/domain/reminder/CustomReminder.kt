package com.ds.localtaskmanager.domain.reminder

import com.ds.localtaskmanager.protocol.Dst1ErrorCode
import com.ds.localtaskmanager.protocol.Dst1ValidationException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

fun resolveCustomReminder(value: String?, taskDate: LocalDate): LocalDateTime? {
    if (value == null) return null
    if (value.contains('T')) return LocalDateTime.parse(value)
    val time = LocalTime.parse(value)
    return (if (time.isBefore(LocalTime.of(4, 0))) taskDate.plusDays(1) else taskDate).atTime(time)
}

fun validateCustomReminder(reminder: LocalDateTime?, deadline: LocalDateTime?, path: String = "a") {
    if (reminder != null && deadline != null && reminder.isAfter(deadline)) {
        throw Dst1ValidationException(Dst1ErrorCode.CONFLICTING_FIELDS, path, "自定义提醒不能晚于截止时间")
    }
}
