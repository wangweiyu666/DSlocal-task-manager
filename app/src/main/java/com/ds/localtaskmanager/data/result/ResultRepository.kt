package com.ds.localtaskmanager.data.result

import com.ds.localtaskmanager.data.ResultRevisionEntity
import com.ds.localtaskmanager.domain.result.DailyResultSnapshot
import com.ds.localtaskmanager.domain.result.DailyResultSummary
import com.ds.localtaskmanager.domain.result.GroupDailyResult

interface ResultRepository {
    suspend fun getDailyResult(taskDate: String): DailyResultSnapshot?
    suspend fun getGroupResult(taskDate: String, groupId: String?): GroupDailyResult?
    suspend fun getRevisionTimeline(taskDate: String): List<ResultRevisionEntity>
    suspend fun getDailySummaries(fromDate: String, throughDate: String): List<DailyResultSummary>
}
