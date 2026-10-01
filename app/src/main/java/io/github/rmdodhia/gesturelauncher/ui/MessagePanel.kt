package io.github.rmdodhia.gesturelauncher.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.rmdodhia.gesturelauncher.ActionRunner
import io.github.rmdodhia.gesturelauncher.core.Action
import io.github.rmdodhia.gesturelauncher.core.SendMessage
import io.github.rmdodhia.gesturelauncher.data.ErrorLog
import kotlinx.coroutines.launch

/**
 * Picking a phone number can be offered by unrelated apps (e.g. file managers), and with several contacts
 * apps Android asks every time. Prefer a real contacts app, the preinstalled one first.
 */
internal fun contactPickerIntent(context: Context): Intent {
    val pick = Intent(Intent.ACTION_PICK, Phone.CONTENT_URI)
    val pm = context.packageManager
    val contactsApps = pm.queryIntentActivities(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CONTACTS), 0)
        .map { it.activityInfo.packageName }.toSet()
    val pickers = pm.queryIntentActivities(pick, 0).map { it.activityInfo }.filter { it.packageName in contactsApps }
    val best = pickers.firstOrNull { it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 } ?: pickers.firstOrNull()
    return best?.let { Intent(pick).setClassName(it.packageName, it.name) } ?: pick
}

/**
 * "Message" tab: pick a recipient with the system contact picker (which grants access to just that one
 * contact, so no contacts permission is needed) or type a number.
 */
@Composable
internal fun MessagePanel(current: SendMessage?, runner: ActionRunner, onPick: (Action) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(current?.name.orEmpty()) }
    var number by remember { mutableStateOf(current?.number.orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.query(uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0).orEmpty()
                    number = c.getString(1).orEmpty()
                    message = null
                }
            }
        } catch (e: Exception) {
            ErrorLog.record("read picked contact", e)
            message = "Couldn't read that contact: ${e.message}"
        }
    }

    val action = SendMessage.from(name, number)
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Opens your messaging app with a new message to this person.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = {
            try {
                picker.launch(contactPickerIntent(context))
            } catch (e: ActivityNotFoundException) {
                ErrorLog.record("open contact picker", e)
                message = "No contacts app found. Type the number below instead."
            }
        }, modifier = Modifier.fillMaxWidth().testTag("pickContact")) { Text("Choose contact") }
        Text("Or type it:", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            name, { name = it }, label = { Text("Name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("messageName"),
        )
        OutlinedTextField(
            number, { number = it; message = null }, label = { Text("Phone number") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            isError = number.isNotBlank() && action == null,
            supportingText = { if (number.isNotBlank() && action == null) Text("Not a phone number") },
            modifier = Modifier.fillMaxWidth().testTag("messageNumber"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(enabled = action != null, onClick = {
                val a = action ?: return@OutlinedButton
                message = null
                scope.launch { message = runner.run(a) ?: "Opened — check it's the right person." }
            }) { Text("Test") }
            Button(
                enabled = action != null,
                onClick = { action?.let(onPick) },
                modifier = Modifier.testTag("useAction"),
            ) { Text("Use this action") }
        }
        message?.let { Text(it, modifier = Modifier.testTag("messageResult")) }
    }
}
