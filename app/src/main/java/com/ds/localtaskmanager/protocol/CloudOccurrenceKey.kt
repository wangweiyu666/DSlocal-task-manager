package com.ds.localtaskmanager.protocol

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val cloudTaskId = Regex("^[A-Za-z0-9_-]{16}$")
private val minuteFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

fun cloudOccurrenceKey(
    taskId: String,
    taskRevision: Int,
    timeZoneVersion: Int,
    scheduledLocalTime: LocalDateTime,
): String {
    require(cloudTaskId.matches(taskId)) { "invalid DST1 task id" }
    require(taskRevision >= 1 && timeZoneVersion >= 1) { "invalid cloud version" }
    return "$taskId:$taskRevision:$timeZoneVersion:${scheduledLocalTime.format(minuteFormatter)}"
}
