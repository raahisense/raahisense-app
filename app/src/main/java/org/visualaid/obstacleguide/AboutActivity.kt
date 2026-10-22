package org.visualaid.obstacleguide

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.visualaid.obstacleguide.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAboutBinding

    override fun attachBaseContext(newBase: Context) {
        val tag = SettingsStore.load(newBase).languageTag
        super.attachBaseContext(Localized.wrap(newBase, tag))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.back.setOnClickListener { finish() }
    }
}
