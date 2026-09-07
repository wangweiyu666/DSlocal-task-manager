package com.ds.localtaskmanager.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

private const val KEY_ALIAS = "dstationery-connected-db-wrap-v1"
private const val PREFERENCES = "connected_database_key"
private const val WRAPPED_KEY = "wrapped_key_v1"

internal fun createConnectivityDatabase(context: Context): AppDatabase {
    val factory = connectedOpenHelperFactory(context)
    val builder = Room.databaseBuilder(context, AppDatabase::class.java, "dst-connected.db")
        .openHelperFactory(factory)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
    if (System.getProperty("dstationery.unitTest") == "true") builder.allowMainThreadQueries()
    return builder.build()
}

internal fun connectedOpenHelperFactory(context: Context): SupportSQLiteOpenHelper.Factory {
    if (System.getProperty("dstationery.unitTest") == "true") return FrameworkSQLiteOpenHelperFactory()
    System.loadLibrary("sqlcipher")
    return SupportOpenHelperFactory(ConnectedDatabaseKey.obtain(context))
}

private object ConnectedDatabaseKey {
    fun obtain(context: Context): ByteArray {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val existing = preferences.getString(WRAPPED_KEY, null)
        if (existing != null) return decrypt(Base64.decode(existing, Base64.NO_WRAP))
        val passphrase = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val wrapped = encrypt(passphrase)
        check(preferences.edit().putString(WRAPPED_KEY, Base64.encodeToString(wrapped, Base64.NO_WRAP)).commit())
        return passphrase
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encrypt(value: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        return cipher.iv + cipher.doFinal(value)
    }

    private fun decrypt(value: ByteArray): ByteArray {
        require(value.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, value.copyOfRange(0, 12)))
        }
        return cipher.doFinal(value.copyOfRange(12, value.size))
    }
}
