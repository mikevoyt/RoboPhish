package com.bayapps.android.robophish.ui

import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.bayapps.android.robophish.R
import com.bayapps.android.robophish.audio.AudienceAudioSettings
import com.bayapps.android.robophish.audio.AudienceDsp

class AudienceAudioActivity : BaseActivity() {
    private val settings by lazy { AudienceAudioSettings.get(this) }
    private lateinit var compare: Button
    private lateinit var state: TextView
    private lateinit var amountLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.audience_audio_screen)
        initializeToolbar()
        val ink = androidx.core.content.ContextCompat.getColor(this, R.color.offline_ink)
        findViewById<android.view.View>(R.id.toolbar).setBackgroundColor(ink)
        window.statusBarColor = ink
        findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawer_layout).setStatusBarBackgroundColor(ink)
        title = getString(R.string.audio_title)
        state = findViewById(R.id.audio_state)
        amountLabel = findViewById(R.id.audio_amount_label)
        compare = findViewById(R.id.audio_compare)
        val enabled = findViewById<SwitchCompat>(R.id.audio_enabled)
        enabled.isChecked = settings.current.enabled
        enabled.setOnCheckedChangeListener { _, value ->
            settings.update(enabled = value)
            settings.compareOriginal = false
            updateState()
        }
        val mode = findViewById<RadioGroup>(R.id.audio_mode)
        mode.check(if (settings.current.mode == AudienceDsp.Mode.CLARITY) R.id.audio_clarity else R.id.audio_rumble)
        mode.setOnCheckedChangeListener { _, checked ->
            settings.update(mode = if (checked == R.id.audio_clarity) AudienceDsp.Mode.CLARITY else AudienceDsp.Mode.RUMBLE)
            updateState()
        }
        findViewById<SeekBar>(R.id.audio_amount).apply {
            max = 100
            progress = (settings.current.amount * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) settings.update(amount = progress / 100f)
                    updateState()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
        compare.setOnClickListener {
            settings.compareOriginal = !settings.compareOriginal
            updateState()
        }
        updateState()
    }

    override fun onPause() {
        settings.compareOriginal = false
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::compare.isInitialized) updateState()
    }

    private fun updateState() {
        val current = settings.current
        amountLabel.text = getString(R.string.audio_amount, (current.amount * 100).toInt())
        compare.isEnabled = current.enabled && current.amount > 0
        compare.setText(if (settings.compareOriginal) R.string.audio_hear_enhanced else R.string.audio_hear_original)
        state.setText(if (!current.enabled || current.amount == 0f || settings.compareOriginal)
            R.string.audio_original_active else R.string.audio_enhanced_active)
    }
}
