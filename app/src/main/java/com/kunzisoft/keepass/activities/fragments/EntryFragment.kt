package com.kunzisoft.keepass.activities.fragments

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.adapters.EntryAttachmentsItemsAdapter
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.element.Attachment
import com.kunzisoft.keepass.database.element.template.TemplateField
import com.kunzisoft.keepass.database.helper.getLocalizedName
import com.kunzisoft.keepass.model.EntryAttachmentState
import com.kunzisoft.keepass.model.EntryInfo
import com.kunzisoft.keepass.model.StreamDirection
import com.kunzisoft.keepass.settings.PreferencesUtil
import com.kunzisoft.keepass.timeout.ClipboardHelper
import com.kunzisoft.keepass.utils.TimeUtil.getDateTimeString
import com.kunzisoft.keepass.utils.UUIDUtils.asHexString
import com.kunzisoft.keepass.view.TemplateView
import com.kunzisoft.keepass.view.hideByFading
import com.kunzisoft.keepass.view.showByFading
import com.kunzisoft.keepass.viewmodels.EntryViewModel

// larry's add
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.kunzisoft.keepass.output.OutputProviderClient

class EntryFragment: DatabaseFragment() {

    private lateinit var rootView: View
    private lateinit var mainSection: View
    private lateinit var advancedSection: View

    private lateinit var templateView: TemplateView

    private lateinit var creationDateView: TextView
    private lateinit var modificationDateView: TextView

    private lateinit var attachmentsContainerView: View
    private lateinit var attachmentsListView: RecyclerView
    private var attachmentsAdapter: EntryAttachmentsItemsAdapter? = null

    private lateinit var customDataView: TextView

    private lateinit var uuidContainerView: View
    private lateinit var uuidReferenceView: TextView

    private var mClipboardHelper: ClipboardHelper? = null

    private val mEntryViewModel: EntryViewModel by activityViewModels()

	// larry
	private var outputClient: OutputProviderClient? = null

    override fun onCreateView(inflater: LayoutInflater,
                              container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        super.onCreateView(inflater, container, savedInstanceState)

        return inflater.inflate(R.layout.fragment_entry, container, false)
    }
		
	// larry add
	override fun onStart() {
		super.onStart()
		val ctx = context ?: return
		if (!PreferencesUtil.isOutputProviderEnabled(ctx)) return

		// no manual binding needed - client binds on-demand when sending
		if (outputClient == null) {
			outputClient = OutputProviderClient(ctx.applicationContext)
		}
	}

	// larry add
	override fun onStop() {
		super.onStop()
		outputClient?.release()
		outputClient = null
	}		
	
    override fun onViewCreated(view: View,
                               savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        context?.let { context ->
            mClipboardHelper = ClipboardHelper(context)
        }

        rootView = view
        // Hide only the first time
        if (savedInstanceState == null) {
            view.isVisible = false
        }

        mainSection = view.findViewById(R.id.entry_section_main)
        advancedSection = view.findViewById(R.id.entry_section_advanced)

        templateView = view.findViewById(R.id.entry_template)
        loadTemplateSettings()

        attachmentsContainerView = view.findViewById(R.id.entry_attachments_container)
        attachmentsListView = view.findViewById(R.id.entry_attachments_list)
        attachmentsListView.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            (itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false
        }

        creationDateView = view.findViewById(R.id.entry_created)
        modificationDateView = view.findViewById(R.id.entry_modified)

        // TODO Custom data
        // customDataView = view.findViewById(R.id.entry_custom_data)

        uuidContainerView = view.findViewById(R.id.entry_UUID_container)
        uuidContainerView.apply {
            visibility = if (PreferencesUtil.showUUID(context)) View.VISIBLE else View.GONE
        }
        uuidReferenceView = view.findViewById(R.id.entry_UUID_reference)

        mEntryViewModel.entryInfoHistory.observe(viewLifecycleOwner) { entryInfoHistory ->
            if (entryInfoHistory != null) {
                templateView.setTemplate(entryInfoHistory.template)
                assignEntryInfo(entryInfoHistory.entryInfo)
                // Smooth appearing
                rootView.showByFading()
                resetAppTimeoutWhenViewFocusedOrChanged(rootView)
            }
        }

        mEntryViewModel.onAttachmentAction.observe(viewLifecycleOwner) { entryAttachmentState ->
            entryAttachmentState?.let {
                if (it.streamDirection != StreamDirection.UPLOAD) {
                    putAttachment(it)
                }
            }
        }

        mEntryViewModel.sectionSelected.observe(viewLifecycleOwner) { entrySection ->
            when (entrySection ?: EntryViewModel.EntrySection.MAIN) {
                EntryViewModel.EntrySection.MAIN -> {
                    mainSection.showByFading()
                    advancedSection.hideByFading()
                }
                EntryViewModel.EntrySection.ADVANCED -> {
                    mainSection.hideByFading()
                    advancedSection.showByFading()
                }
            }
        }
    }

    override fun onDatabaseRetrieved(database: ContextualDatabase) {
        context?.let { context ->
            attachmentsAdapter = EntryAttachmentsItemsAdapter(context)
            attachmentsAdapter?.database = database
        }

        attachmentsListView.adapter = attachmentsAdapter
    }

    private fun loadTemplateSettings() {
        context?.let { context ->
            templateView.setFirstTimeAskAllowCopyProtectedFields(PreferencesUtil.isFirstTimeAskAllowCopyProtectedFields(context))
            templateView.setAllowCopyProtectedFields(PreferencesUtil.allowCopyProtectedFields(context))
        }
    }

    private fun assignEntryInfo(entryInfo: EntryInfo?) {
        // Set copy buttons
        templateView.apply {
            setOnAskCopySafeClickListener {
                showClipboardDialog()
            }

            setOnCopyActionClickListener { field ->
                mClipboardHelper?.timeoutCopyToClipboard(
                    TemplateField.getLocalizedName(context, field.name),
                    field.protectedValue.stringValue,
                    field.protectedValue.isProtected
                )
            }
			
			// larry add: Send button handler (wired from TemplateView -> TextFieldView.sendButton)
			setOnSendActionClickListener { field ->
				val ctx = requireContext()

				if (!PreferencesUtil.isOutputProviderEnabled(ctx)) {
					Toast.makeText(ctx, "Output provider is disabled", Toast.LENGTH_SHORT).show()
					return@setOnSendActionClickListener
				}

				if (PreferencesUtil.getOutputProviderComponentName(ctx) == null) {
					Toast.makeText(ctx, "Select an output provider first", Toast.LENGTH_SHORT).show()
					return@setOnSendActionClickListener
				}

				val client = outputClient ?: OutputProviderClient(ctx.applicationContext).also {
					outputClient = it
				}

				templateView.setSendInProgress(true)

				// Optional: timeout UI fallback
				val ui = Handler(Looper.getMainLooper())
				val fallback = Runnable {
					templateView.setSendInProgress(false)
					Toast.makeText(ctx, "Send failed: not responding", Toast.LENGTH_SHORT).show()
				}
				ui.postDelayed(fallback, 8_000)

				val mode = "pass"
				val entryTitle = entryInfo?.title
				val entryUuid = entryInfo?.id?.asHexString()

				client.sendWithResult(
					mode = mode,
					username = null,
					password = field.protectedValue.stringValue,
					otp = null,
					entryTitle = entryTitle,
					entryUuid = entryUuid
				) { rc: Int ->
					ui.removeCallbacks(fallback)
					templateView.setSendInProgress(false)

					val msg = if (rc == 0) "Sent OK" else "Send failed (code=$rc)"
					Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
				}
			}
			// larry end			
        }

        // Populate entry views
        templateView.setEntryInfo(entryInfo)

        // OTP timer updated
        templateView.setOnOtpElementUpdated { otpElementUpdated ->
            mEntryViewModel.onOtpElementUpdated(otpElementUpdated)
        }

        // Manage attachments
        assignAttachments(entryInfo?.attachments ?: listOf())

        // Assign dates
        creationDateView.text = entryInfo?.creationTime?.getDateTimeString(resources)
        modificationDateView.text = entryInfo?.lastModificationTime?.getDateTimeString(resources)

        // TODO Custom data
        // customDataView.text = entryInfo?.customData?.toString()

        // Assign special data
        uuidReferenceView.text = entryInfo?.id?.asHexString()
    }

    private fun showClipboardDialog() {
        context?.let {
            AlertDialog.Builder(it)
                .setMessage(
                    getString(R.string.allow_copy_password_warning) +
                            "\n\n" +
                            getString(R.string.clipboard_warning)
                )
                .create().apply {
                    setButton(AlertDialog.BUTTON_POSITIVE, getText(R.string.enable)) { dialog, _ ->
                        PreferencesUtil.setAllowCopyPasswordAndProtectedFields(context, true)
                        finishDialog(dialog)
                    }
                    setButton(AlertDialog.BUTTON_NEGATIVE, getText(R.string.disable)) { dialog, _ ->
                        PreferencesUtil.setAllowCopyPasswordAndProtectedFields(context, false)
                        finishDialog(dialog)
                    }
                    show()
                }
        }
    }

    private fun finishDialog(dialog: DialogInterface) {
        dialog.dismiss()
        loadTemplateSettings()
        templateView.reload()
    }

    /* -------------
     * Attachments
     * -------------
     */

    private fun assignAttachments(attachments: List<Attachment>) {
        attachmentsContainerView.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE
        attachmentsAdapter?.assignItems(attachments.map {
            EntryAttachmentState(it, StreamDirection.DOWNLOAD)
        })
        attachmentsAdapter?.onItemClickListener = { item ->
            mEntryViewModel.onAttachmentSelected(item.attachment)
        }
    }

    fun putAttachment(attachmentToDownload: EntryAttachmentState) {
        attachmentsAdapter?.putItem(attachmentToDownload)
    }

    /* -------------
     * Education
     * -------------
     */

    fun firstEntryFieldCopyView(): View? {
        return try {
            templateView.getActionImageView()
        } catch (e: Exception) {
            null
        }
    }

    fun launchEntryCopyEducationAction() {
        val appNameString = getString(R.string.app_name)
        mClipboardHelper?.timeoutCopyToClipboard(appNameString, appNameString)
    }

    companion object {

        fun getInstance(): EntryFragment {
            return EntryFragment().apply {
                arguments = Bundle()
            }
        }
    }
}
