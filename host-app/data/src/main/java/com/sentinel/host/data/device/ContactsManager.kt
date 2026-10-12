package com.sentinel.host.data.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads contacts, emails, ICE tags, and emergency details from the host device via [ContactsContract].
 * Features pagination, email association, and deduplication.
 */
@Singleton
class ContactsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "Sentinel:Contacts"
    }

    fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Reads contacts from the device.
     * Returns a map containing:
     * - "permissionGranted": Boolean
     * - "total": Int
     * - "returned": Int
     * - "offset": Int
     * - "contacts": List<Map<String, String>>
     * - "emergencyContact": Map<String, String>? (if ICE/Emergency match found)
     *
     * @param limit Maximum contacts to return. 0 or negative means fetch all contacts.
     * @param offset Number of contacts to skip for pagination.
     */
    fun getContacts(limit: Int = 0, offset: Int = 0): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()

        if (!hasContactsPermission()) {
            Log.w(TAG, "READ_CONTACTS permission not granted on host")
            result["permissionGranted"] = false
            result["error"] = "READ_CONTACTS permission not granted on host device"
            result["total"] = 0
            result["contacts"] = emptyList<Map<String, String>>()
            return result
        }

        result["permissionGranted"] = true

        val contactsList = mutableListOf<Map<String, String>>()
        var emergencyContact: Map<String, String>? = null

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )

            cursor?.let { c ->
                val idIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)

                var matchedCount = 0
                val seenNumbers = mutableSetOf<String>()

                while (c.moveToNext()) {
                    val rawNumber = if (numberIndex >= 0) c.getString(numberIndex) ?: "" else ""
                    val cleanNumber = rawNumber.replace("\\s+".toRegex(), "")

                    if (cleanNumber.isNotBlank() && !seenNumbers.contains(cleanNumber)) {
                        seenNumbers.add(cleanNumber)

                        if (matchedCount >= offset && (limit <= 0 || contactsList.size < limit)) {
                            val contactId = if (idIndex >= 0) c.getString(idIndex) ?: "" else ""
                            val name = if (nameIndex >= 0) c.getString(nameIndex) ?: "" else ""
                            val typeCode = if (typeIndex >= 0) c.getInt(typeIndex) else ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
                            val typeStr = when (typeCode) {
                                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
                                ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
                                ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
                                else -> "Other"
                            }

                            val isIce = name.contains("ICE", ignoreCase = true) ||
                                    name.contains("Emergency", ignoreCase = true)

                            val email = if (contactId.isNotBlank()) getContactEmail(contactId) else ""

                            val entry = mutableMapOf(
                                "id" to contactId,
                                "name" to name,
                                "phone" to rawNumber,
                                "type" to typeStr,
                                "email" to email,
                                "isEmergency" to isIce.toString()
                            )
                            contactsList.add(entry)

                            if (isIce && emergencyContact == null) {
                                emergencyContact = entry
                            }
                        }
                        matchedCount++
                    }
                }
                result["total"] = seenNumbers.size
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query contacts: ${e.message}", e)
            result["error"] = e.message ?: "Failed to read contacts"
        } finally {
            cursor?.close()
        }

        result["returned"] = contactsList.size
        result["offset"] = offset
        result["contacts"] = contactsList
        emergencyContact?.let {
            result["emergencyContact"] = it
        }

        return result
    }

    private fun getContactEmail(contactId: String): String {
        var emailCursor: Cursor? = null
        return try {
            emailCursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Email.DATA),
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                arrayOf(contactId),
                null
            )
            if (emailCursor != null && emailCursor.moveToFirst()) {
                emailCursor.getString(0) ?: ""
            } else ""
        } catch (_: Exception) {
            ""
        } finally {
            emailCursor?.close()
        }
    }
}
