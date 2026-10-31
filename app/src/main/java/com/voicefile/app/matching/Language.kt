package com.voicefile.app.matching

/**
 * Languages the user can select. [tag] is both the UI key and the ISO code
 * passed to the offline Whisper model as its language hint.
 */
enum class Language(val display: String, val tag: String) {
    EN("English", "en"),
    TA("தமிழ் — Tamil", "ta"),
    AR("العربية — Arabic", "ar"),
}
