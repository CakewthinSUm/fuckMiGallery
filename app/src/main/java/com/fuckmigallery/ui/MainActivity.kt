package com.fuckmigallery.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fuckmigallery.ConfigContract
import com.fuckmigallery.ConfigStore
import com.fuckmigallery.GalleryResolver
import com.fuckmigallery.MiuiPackages
import com.fuckmigallery.R
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.concurrent.thread

/**
 * Settings UI. The module does nothing from here — this screen only persists the
 * choice via [ConfigStore], which the hook reads out of process.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var enabledSwitch: MaterialSwitch
    private lateinit var verboseSwitch: MaterialSwitch
    private lateinit var galleryDropdown: AutoCompleteTextView
    private lateinit var statusText: TextView

    /** Dropdown rows, index-aligned with [galleryPackages]. */
    private val galleryLabels = mutableListOf<String>()
    private val galleryPackages = mutableListOf<String>()

    private var enabled = false
    private var targetPackage = ConfigContract.TARGET_FOLLOW_SYSTEM
    private var verboseLog = false

    /** True while programmatically restoring switch state, so listeners do not write back. */
    private var binding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        enabledSwitch = findViewById(R.id.switch_enabled)
        verboseSwitch = findViewById(R.id.switch_verbose)
        galleryDropdown = findViewById(R.id.dropdown_gallery)
        statusText = findViewById(R.id.text_status)

        val (e, t, v) = ConfigStore.read(this)
        enabled = e
        targetPackage = t
        verboseLog = v

        enabledSwitch.isChecked = enabled
        verboseSwitch.isChecked = verboseLog

        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            enabled = checked
            persist()
        }
        verboseSwitch.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            verboseLog = checked
            persist()
        }
        // A dialog, not the AutoCompleteTextView's own popup. The popup refused to
        // open under Material3 regardless of focus/clickable settings, and a dialog
        // is both reliable and easier to read on a long list of galleries.
        galleryDropdown.setOnClickListener { showGalleryPicker() }

        loadGalleries()
    }

    override fun onResume() {
        super.onResume()
        // Re-read on every resume: the process can outlive a data clear, and the
        // in-memory fields would otherwise show a selection that no longer exists
        // on disk.
        val (e, t, v) = ConfigStore.read(this)
        enabled = e
        targetPackage = t
        verboseLog = v

        // Assigning isChecked fires the listeners, which would call persist() and
        // write the just-read values straight back — harmless in itself, but it also
        // raced with real edits. Suppress it while binding.
        binding = true
        enabledSwitch.isChecked = enabled
        verboseSwitch.isChecked = verboseLog
        binding = false

        if (galleryPackages.isNotEmpty()) applySavedSelection()
        refreshStatus()
    }

    /** Set by [persist]; drives the "did it actually reach the hook" hint. */
    private var lastPublishOk = true

    private fun persist() {
        lastPublishOk = ConfigStore.write(this, enabled, targetPackage, verboseLog)
        refreshStatus()
    }

    /**
     * Enumerating installed galleries means querying the PackageManager for every app
     * that handles an image, which is slow enough to keep off the main thread.
     */
    private fun loadGalleries() {
        galleryDropdown.isEnabled = false
        thread(name = "gallery-scan") {
            val entries = GalleryResolver.listGalleries(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                galleryLabels.clear()
                galleryPackages.clear()

                galleryLabels += getString(R.string.gallery_follow_system)
                galleryPackages += ConfigContract.TARGET_FOLLOW_SYSTEM

                entries.forEach {
                    val suffix = if (MiuiPackages.isXiaomi(it.packageName)) {
                        getString(R.string.gallery_miui_suffix)
                    } else {
                        ""
                    }
                    galleryLabels += "${it.label}$suffix"
                    galleryPackages += it.packageName
                }

                galleryDropdown.setAdapter(
                    ArrayAdapter(this, android.R.layout.simple_list_item_1, galleryLabels),
                )
                galleryDropdown.isEnabled = true
                applySavedSelection()
                refreshStatus()
            }
        }
    }

    /** Single-choice dialog over the scanned galleries. */
    private fun showGalleryPicker() {
        if (galleryLabels.isEmpty()) return
        val current = galleryPackages.indexOf(targetPackage).takeIf { it >= 0 } ?: 0
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.label_gallery)
            .setSingleChoiceItems(
                galleryLabels.toTypedArray(),
                current,
            ) { dialog, which ->
                targetPackage = galleryPackages[which]
                persist()
                applySavedSelection()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applySavedSelection() {
        val index = galleryPackages.indexOf(targetPackage).takeIf { it >= 0 } ?: 0
        galleryDropdown.setText(galleryLabels.getOrElse(index) { "" }, false)
    }

    private fun refreshStatus() {
        val targetLabel = when {
            !enabled -> getString(R.string.status_disabled)
            targetPackage == ConfigContract.TARGET_FOLLOW_SYSTEM -> getString(R.string.status_follow_system)
            MiuiPackages.isXiaomi(targetPackage) -> getString(R.string.status_same_as_miui)
            else -> targetPackage
        }
        statusText.text = if (lastPublishOk) {
            getString(R.string.status_line, targetLabel)
        } else {
            getString(R.string.status_line, targetLabel) + "\n" + getString(R.string.status_no_root)
        }
        statusText.visibility = View.VISIBLE
    }
}
