package com.ds.localtaskmanager.data

import android.content.Context
import androidx.room.Room

internal fun createConnectivityDatabase(context: Context): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, "dst-sub.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
        .build()
