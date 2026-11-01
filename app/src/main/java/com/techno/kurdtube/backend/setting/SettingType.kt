package com.techno.kurdtube.backend.setting

sealed class SettingType(val default: Any) {

    class Switch(default: Boolean) : SettingType(default)

    class Slider(default: Int, val range: ClosedRange<Int>, val step: Int) : SettingType(default)

    /**
     * @param options the values that are saved in the preferences.
     * @param labelIds optional string resources that are displayed instead of the
     * raw values, so the options can be translated without changing saved data.
     */
    class Select(
        default: String,
        val options: Array<String>,
        val labelIds: IntArray? = null
    ) : SettingType(default)

}
