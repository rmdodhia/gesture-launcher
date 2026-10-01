package io.github.rmdodhia.gesturelauncher.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactPickerTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val pm = shadowOf(context.packageManager)
    private val pick = Intent(Intent.ACTION_PICK, Phone.CONTENT_URI)
    private val contactsApps = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS)

    private fun info(pkg: String, cls: String, system: Boolean) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = pkg
            name = cls
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg
                flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
            }
        }
    }

    @Test
    fun prefersPreinstalledContactsAppAndSkipsOtherPickers() {
        // Like a Galaxy with Google Contacts installed and a file manager that also claims phone picking.
        pm.addResolveInfoForIntent(pick, info("com.files", "com.files.Picker", system = false))
        pm.addResolveInfoForIntent(pick, info("com.google.contacts", "com.google.contacts.Picker", system = false))
        pm.addResolveInfoForIntent(pick, info("com.samsung.contacts", "com.samsung.contacts.Picker", system = true))
        pm.addResolveInfoForIntent(contactsApps, info("com.google.contacts", "com.google.contacts.People", system = false))
        pm.addResolveInfoForIntent(contactsApps, info("com.samsung.contacts", "com.samsung.contacts.People", system = true))

        val intent = contactPickerIntent(context)
        assertEquals("com.samsung.contacts.Picker", intent.component?.className)
        assertEquals(Intent.ACTION_PICK, intent.action)
        assertEquals(Phone.CONTENT_URI, intent.data)
    }

    @Test
    fun fallsBackToSystemChoiceWithoutAContactsApp() {
        pm.addResolveInfoForIntent(pick, info("com.files", "com.files.Picker", system = false))
        assertNull(contactPickerIntent(context).component)
    }
}
