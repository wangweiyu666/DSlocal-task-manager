package com.ds.localtaskmanager.ui

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val deadlineDisplayFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")

fun formatDeadlineForDisplay(value: String): String =
    runCatching {
        LocalDateTime.parse(value).format(deadlineDisplayFormatter)
    }.getOrElse {
        value.replace('T', ' ')
    }
