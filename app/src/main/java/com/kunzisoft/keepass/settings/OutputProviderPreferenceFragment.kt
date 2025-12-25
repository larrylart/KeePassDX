package com.kunzisoft.keepass.settings

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.settings.preference.OutputProviderRowPreference

class OutputProviderPreferenceFragment : NestedSettingsFragment() {

    companion object {
        private const val ACTION_OUTPUT_CREDENTIALS_SERVICE =
            "org.keepassdx.intent.action.OUTPUT_CREDENTIALS_SERVICE"

        private const val KEY_PROVIDER = "pref_output_provider_component" 
        private const val KEY_VERIFY   = "pref_output_provider_verify"   
    }

    private lateinit var providerPref: OutputProviderRowPreference

    private data class Provider(
        val component: ComponentName,
        val label: String
    )

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshProviders()
        }
    }

    override fun onCreateScreenPreference(
        screen: Screen,
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        setPreferencesFromResource(R.xml.preferences_output_provider, rootKey)

        providerPref = requireNotNull(findPreference(KEY_PROVIDER))

        // Row toggle: treat as "Enable output provider"
		providerPref.onToggleChanged = { enabled ->
			val ctx = requireContext()
			PreferencesUtil.setOutputProviderEnabled(ctx, enabled)

			if (!enabled) {
				// setOutputProviderEnabled() already clears component, but keeping this is fine too
				PreferencesUtil.setOutputProviderComponent(ctx, "")
			}

			refreshProviders()
		}

        // Dropdown selection
		providerPref.onProviderSelected = { componentFlattened: String, _label: String ->
			val ctx = requireContext()
			PreferencesUtil.setOutputProviderComponent(ctx, componentFlattened)
			PreferencesUtil.setOutputProviderEnabled(ctx, true)   // force enable
			providerPref.setSwitchChecked(true)
			Toast.makeText(ctx, "Selected output provider", Toast.LENGTH_SHORT).show()
		}

        // Verification toggle (separate switch preference)
		findPreference<Preference>(KEY_VERIFY)?.setOnPreferenceChangeListener { _, newValue ->
			PreferencesUtil.setOutputProviderVerify(requireContext(), newValue as Boolean)
			true
		}
		
        refreshProviders()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            requireContext(),
            packageChangeReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        refreshProviders()
    }

    override fun onStop() {
        super.onStop()
        try { requireContext().unregisterReceiver(packageChangeReceiver) } catch (_: Exception) {}
    }

    private fun refreshProviders() {
        val ctx = requireContext()
        val pm = ctx.packageManager

        val providers = queryProviders(pm)

        val entries = if (providers.isEmpty()) {
            listOf(getString(R.string.no_output_provider_found))
        } else {
            providers.map { it.label }
        }

        val values = if (providers.isEmpty()) {
            listOf("")
        } else {
            providers.map { it.component.flattenToString() }
        }

        val saved = PreferencesUtil.getOutputProviderComponent(ctx).orEmpty()

        // Enable switch ON if we have a saved provider
		val enabled = PreferencesUtil.isOutputProviderEnabled(ctx)
		providerPref.setSwitchChecked(enabled)

        // Populate dropdown + preselect
        providerPref.setData(entries, values, saved)

        // If saved provider no longer exists, clear it
        if (saved.isNotBlank() && values.none { it == saved }) {
            PreferencesUtil.setOutputProviderComponent(ctx, "")
            providerPref.setSwitchChecked(false)
            providerPref.setData(entries, values, "")
        }
    }

    private fun queryProviders(pm: PackageManager): List<Provider> {
        val intent = Intent(ACTION_OUTPUT_CREDENTIALS_SERVICE)
        //val resolveInfos = pm.queryIntentServices(intent, PackageManager.MATCH_DEFAULT_ONLY)
		// more permissive
		val resolveInfos = pm.queryIntentServices(intent, 0)

        return resolveInfos
            .mapNotNull { ri ->
                val si = ri.serviceInfo ?: return@mapNotNull null
                val comp = ComponentName(si.packageName, si.name)

                val label = try {
                    val appInfo = pm.getApplicationInfo(si.packageName, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    si.packageName
                }

                Provider(comp, label)
            }
            .sortedBy { it.label.lowercase() }
    }
}
