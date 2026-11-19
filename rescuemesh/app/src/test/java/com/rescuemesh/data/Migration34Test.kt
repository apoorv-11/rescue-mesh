package com.rescuemesh.data

import androidx.sqlite.db.SupportSQLiteDatabase
import com.rescuemesh.data.local.RescueMeshDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Migration(3,4) — FIX5 + Prepare (19 Nov): the `photo_upload` table
 * (one receipt per record whose full photo the server accepted) and
 * the `saved_guides` table (Prepare guides the user marked Saved).
 * Checked the same way Migration(2,3) is verified in this project:
 * the migration's SQL is executed against a recording
 * [SupportSQLiteDatabase] proxy and compared with the named constants
 * the migration itself uses. (A real on-device SQLite migration proof
 * stays a device item.)
 */
class Migration34Test {

    @Test
    fun `migration spans v3 to v4 and creates exactly the two new tables`() {
        val migration = RescueMeshDatabase.MIGRATION_3_4
        assertEquals(3, migration.startVersion)
        assertEquals(4, migration.endVersion)

        val executed = mutableListOf<String>()
        val db = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args?.firstOrNull() is String) {
                executed += args[0] as String
            }
            defaultValueFor(method.returnType)
        } as SupportSQLiteDatabase

        migration.migrate(db)

        assertEquals(
            listOf(
                RescueMeshDatabase.PHOTO_UPLOAD_CREATE_SQL,
                RescueMeshDatabase.SAVED_GUIDES_CREATE_SQL
            ),
            executed
        )
        assertTrue(executed[0].contains("CREATE TABLE IF NOT EXISTS `photo_upload`"))
        assertTrue(executed[0].contains("PRIMARY KEY(`messageId`)"))
        assertTrue(executed[1].contains("CREATE TABLE IF NOT EXISTS `saved_guides`"))
        assertTrue(executed[1].contains("PRIMARY KEY(`guideId`)"))
    }

    private fun defaultValueFor(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Double.TYPE -> 0.0
        java.lang.Float.TYPE -> 0f
        java.lang.Character.TYPE -> 0.toChar()
        else -> null
    }
}
