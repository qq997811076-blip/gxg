package com.example.ipspeed

/**
 * Independent copy of the original IP speed test screen.
 * All preferences and working files are isolated from MainActivity.
 */
class OfficialActivity : MainActivity() {
    override val prefs by lazy { getSharedPreferences("settings_official", MODE_PRIVATE) }
    override val isOfficialMode: Boolean = true
}
