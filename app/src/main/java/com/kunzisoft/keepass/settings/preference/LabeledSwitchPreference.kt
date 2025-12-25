package com.kunzisoft.keepass.settings.preference

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.materialswitch.MaterialSwitch
import com.kunzisoft.keepass.R

class LabeledSwitchPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    private var switchView: MaterialSwitch? = null
    private var suppressListener = false

    init {
        layoutResource = R.layout.pref_labeled_switch_row
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val titleView = holder.findViewById(R.id.switchTitle) as? TextView
        val sw = holder.findViewById(R.id.rowSwitch) as? MaterialSwitch
        switchView = sw

        titleView?.text = title ?: ""

        val checked = getPersistedBoolean(false)

        sw?.apply {
            setOnCheckedChangeListener(null)
            isChecked = checked

            setOnCheckedChangeListener { _, isChecked ->
                if (suppressListener) return@setOnCheckedChangeListener

                if (callChangeListener(isChecked)) {
                    persistBoolean(isChecked)
                } else {
                    suppressListener = true
                    this.isChecked = !isChecked
                    suppressListener = false
                }
            }
        }
    }

    fun setCheckedFromCode(value: Boolean) {
        persistBoolean(value)
        suppressListener = true
        switchView?.isChecked = value
        suppressListener = false
    }
}
