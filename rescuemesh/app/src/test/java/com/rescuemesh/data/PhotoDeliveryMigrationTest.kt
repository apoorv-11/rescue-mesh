package com.rescuemesh.data

import androidx.sqlite.db.SupportSQLiteDatabase
import com.rescuemesh.data.local.RescueMeshDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Migration(2,3) — the `photo_delivery` table (device fix, 14 Nov),
 * checked the same way Migration(1,2) is verified in this project:
 * the database file compiles from source in the JVM harness, and here
 * the migration's SQL is executed against a recording
 * [SupportSQLiteDatabase] proxy and compared with the named constant
 * the migration itself uses. (A real on-device SQLite migration proof
 * stays a device item, exactly as for v1→v2.)
 */
class PhotoDeliveryMigrationTest {

    @Test
    fun `migration spans v2 to v3 and creates exactly the photo_delivery table`() {
        val migration = RescueMeshDatabase.MIGRATION_2_3
        assertEquals(2, migration.startVersion)
        assertEquals(3, migration.endVersion)

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

        assertEquals(listOf(RescueMeshDatabase.PHOTO_DELIVERY_CREATE_SQL), executed)
        val sql = executed.single()
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `photo_delivery`"))
        assertTrue(sql.contains("`messageId` TEXT NOT NULL"))
        assertTrue(sql.contains("`peerDeviceId` TEXT NOT NULL"))
        assertTrue(sql.contains("`deliveredAt` INTEGER NOT NULL"))
        assertTrue(sql.contains("PRIMARY KEY(`messageId`, `peerDeviceId`)"))
    }

    @Test
    fun `the create SQL declares the composite primary key Room expects for the entity`() {
        // PhotoDeliveryEntity's primaryKeys = ["messageId", "peerDeviceId"]:
        // if the hand-written SQL and Room's generated schema ever drift,
        // Room's post-migration validation fails on device — pin the key
        // columns AND their order here.
        val sql = RescueMeshDatabase.PHOTO_DELIVERY_CREATE_SQL
        val pk = sql.substringAfter("PRIMARY KEY(").substringBefore(")")
        assertEquals("`messageId`, `peerDeviceId`", pk)
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
