package com.kunzisoft.keepass.settings

import android.os.Bundle
import androidx.fragment.app.Fragment

class OutputProviderSettingsActivity : SettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mTimeoutEnable = false
        setTitle(NestedSettingsFragment.Screen.OUTPUT_PROVIDER)
    }
    override fun retrieveMainFragment(): Fragment {
        return NestedSettingsFragment.newInstance(NestedSettingsFragment.Screen.OUTPUT_PROVIDER)
    }
}
