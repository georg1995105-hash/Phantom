// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.compat.ClipboardManagerCompat
import helium314.keyboard.event.Event
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.isValidNumber
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.databinding.ClipboardSuggestionBinding
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.InputTypeUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.smart.SmartPrefs
import helium314.keyboard.smart.clipboard.SmartClipboard
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ClipboardHistoryManager(
        private val latinIME: LatinIME
) : ClipboardManager.OnPrimaryClipChangedListener {

    private lateinit var clipboardManager: ClipboardManager
    private var clipboardSuggestionView: View? = null
    private var clipboardDao: ClipboardDao? = null
    private val imageWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var tempPrimaryClip = false

    private val screenshots = helium314.keyboard.smart.clipboard.ScreenshotHistory(latinIME, allowed = {
        val current = latinIME.mSettings.current
        // Field protection only describes the editor the keyboard is shown in. With the keyboard hidden,
        // the attributes are those of an earlier field and must not discard unrelated screenshots.
        val protectedField = latinIME.isInputViewShown && (current.mInputAttributes.mNoLearning ||
            InputTypeUtils.isPasswordInputType(latinIME.currentInputEditorInfo?.inputType ?: 0))
        current.mClipboardHistoryEnabled && !current.mIncognitoModeEnabled && !protectedField
    }, onSaved = {
        dontShowCurrentSuggestion = false
        latinIME.svOnScreenshotSaved()
    })
    fun onInputViewStarted() { screenshots.start() }
    fun onCreate() {
        screenshots.start()
        clipboardManager = latinIME.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(this)
        clipboardDao = ClipboardDao.getInstance(latinIME)
        if (latinIME.mSettings.current.mClipboardHistoryEnabled)
            fetchPrimaryClip()
    }

    fun onDestroy() {
        screenshots.close()
        imageWorker.shutdownNow()
        clipboardManager.removePrimaryClipChangedListener(this)
    }

    override fun onPrimaryClipChanged() {
        latinIME.prefs().edit().remove("sv_clip_dismissed_primary").apply()
        // Make sure we read clipboard content only if history settings is set
        if (latinIME.mSettings.current.mClipboardHistoryEnabled) {
            fetchPrimaryClip()
            dontShowCurrentSuggestion = false
        }
    }

    // todo for later
    //  setting whether to store sensitive clip data?
    //  care about other clip items than first?
    private fun fetchPrimaryClip() {
        if (tempPrimaryClip) return // avoid updating history
        val clipData = clipboardManager.primaryClip ?: return
        if (clipData.itemCount == 0) return
        val clipItem = clipData.getItemAt(0) ?: return
        val description = clipData.description ?: return
        val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)
        // never keep content Android marks as sensitive (passwords, OTPs), nor anything copied in incognito mode
        if (ClipboardManagerCompat.getClipSensitivity(description) == true) return
        if (latinIME.mSettings.current.mIncognitoModeEnabled ||
            InputTypeUtils.isPasswordInputType(latinIME.currentInputEditorInfo?.inputType ?: 0)) return

        val dismissed = latinIME.prefs().getString("sv_clip_dismissed_primary", null)
        if (dismissed != null && dismissed == primaryIdentity(clipData)) return
        if (description.hasMimeType("image/*")) {
            if (!latinIME.prefs().getBoolean(Settings.PREF_CLIPBOARD_USE_FILES, Defaults.PREF_CLIPBOARD_USE_FILES)) return
            if (!maySaveFromUri(clipItem.uri, latinIME)) { imageReadFailed(); return }
            imageWorker.execute {
                if (clipboardDao?.addClipUri(timeStamp, false, clipItem.uri, description, latinIME) != true) imageReadFailed()
            }
        } else if (description.hasMimeType("text/*")) {
            val content = clipItem.coerceToText(latinIME)
            if (TextUtils.isEmpty(content)) return
            clipboardDao?.addClip(timeStamp, false, content.toString())
        } else if (maySaveFromUri(clipItem.uri, latinIME)) {
            imageWorker.execute { clipboardDao?.addClipUri(timeStamp, false, clipItem.uri, description, latinIME) }
        }
    }

    fun getPrimaryClipIfText(): String? {
        if (tempPrimaryClip) return null // avoid updating history
        val clipData = clipboardManager.primaryClip ?: return null
        if (clipData.itemCount == 0) return null
        val clipItem = clipData.getItemAt(0) ?: return null
        return if (clipData.description?.hasMimeType("image/*") != true && clipData.description?.hasMimeType("text/*") == true)
            clipItem.coerceToText(latinIME).toString().takeIf { it.isNotEmpty() }
        else null
    }

    // fallback method because in some apps there is no supported mime type and commitContend does nothing,
    // but KeyEvent.KEYCODE_PASTE for pasting from primary clip works fine
    // (actually we do change the primary clip, but (try to) revert immediately)
    fun pasteWithoutChangingClips(content: InputContentInfoCompat) {
        Log.d(TAG, "trying fallback pasting with system clipboard")
        val primaryClip = clipboardManager.primaryClip
        val tempClip = ClipData(content.description, ClipData.Item(content.contentUri))
        tempPrimaryClip = true
        clipboardManager.setPrimaryClip(tempClip)
        latinIME.onEvent(Event.createSoftwareKeypressEvent(KeyCode.CLIPBOARD_PASTE, 0,
            Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false))
        tempPrimaryClip = false
        if (primaryClip == null)
            return
        // we need to wait a little before switching back to the original primary clip
        // a. it can happen that we switch back before the pasting has started, in that case we only past the primary clip
        // b. if we switch while the clip is pasted, it might crash the app (tested with joplin and logseq)
        // todo: replacing the current primary clip is far from ideal, try finding a different way
        GlobalScope.launch {
            delay(500)
            try {
                clipboardManager.setPrimaryClip(primaryClip)
            } catch (e: Exception) {
                Log.i(TAG, "could not go back to old primary clip", e)
                // happens wen the clip was a file
                // try to find it in out clipboard entries
                val clip = clipboardDao?.getAll()?.firstOrNull { it.timeStamp == ClipboardManagerCompat.getClipTimestamp(primaryClip) }
                if (clip?.filename != null)
                    clipboardManager.setPrimaryClip(ClipData(
                        ClipDescription(clip.text, clip.mimeTypes?.toTypedArray()),
                        ClipData.Item(clip.getContentUri(latinIME))
                    ))
                else if (clip != null)
                    clipboardManager.setPrimaryClip(ClipData(
                        ClipDescription("", arrayOf("text/*")),
                        ClipData.Item(clip.text)
                    ))
            }
        }
    }

    fun toggleClipPinned(id: Long) {
        clipboardDao?.togglePinned(id)
    }

    fun clearHistory() {
        clipboardDao?.clearNonPinned()
        ClipboardManagerCompat.clearPrimaryClip(clipboardManager)
        removeClipboardSuggestion()
    }

    fun canRemove(index: Int) = clipboardDao?.isPinned(index) == false

    fun removeEntry(index: Int) {
        if (canRemove(index))
            clipboardDao?.deleteClipAt(index)
    }

    fun sortHistoryEntries() {
        clipboardDao?.sort()
    }

    // We do not want to update history while user is visualizing it, so we check retention only
    // when history is about to be shown
    fun prepareClipboardHistory() { screenshots.start(); clipboardDao?.clearOldClips(true) }

    fun getHistorySize() = clipboardDao?.count() ?: 0

    fun getHistoryEntry(position: Int) = clipboardDao?.getAt(position)

    fun getHistoryEntryContent(id: Long) = clipboardDao?.get(id)

    fun getAllEntries(): List<ClipboardHistoryEntry> = clipboardDao?.getAll()?.toList().orEmpty()

    fun setPinned(ids: Collection<Long>, pinned: Boolean) { clipboardDao?.setPinned(ids, pinned) }

    fun deleteIds(ids: Collection<Long>) {
        latinIME.prefs().edit().putString("sv_clip_dismissed_primary", primaryIdentity()).apply()
        clipboardDao?.deleteIds(ids)
    }

    private fun imageReadFailed() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(latinIME, R.string.sv_clip_save_failed, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** A deleted current clip must not reappear merely because the IME process restarts. */
    private fun primaryIdentity(clip: ClipData? = clipboardManager.primaryClip): String? {
        if (clip == null) return null
        if (clip.itemCount == 0 || ClipboardManagerCompat.getClipSensitivity(clip.description) == true) return null
        if (latinIME.mSettings.current.mIncognitoModeEnabled || InputTypeUtils.isPasswordInputType(latinIME.currentInputEditorInfo?.inputType ?: 0)) return null
        if (android.os.Build.VERSION.SDK_INT >= 26) return clip.description.timestamp.toString()
        val item = clip.getItemAt(0)
        val value = item.uri?.toString() ?: item.text?.toString() ?: return null
        return java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun moveInGroup(id: Long, up: Boolean) = clipboardDao?.moveInGroup(id, up) == true

    fun setHistoryChangeListener(listener: ClipboardDao.Listener?) {
        clipboardDao?.listener = listener
    }

    private fun isClipSensitive(inputType: Int): Boolean {
        ClipboardManagerCompat.getClipSensitivity(clipboardManager.primaryClip?.description)?.let { return it }
        return InputTypeUtils.isPasswordInputType(inputType)
    }

    fun getClipboardSuggestionView(editorInfo: EditorInfo?, parent: ViewGroup?): View? {
        // maybe no need to create a new view
        // but a cache has to consider a few possible changes, so better don't implement without need
        clipboardSuggestionView = null

        // get the content, or return null
        if (!latinIME.mSettings.current.mSuggestClipboardContent) return null
        val prefs = latinIME.prefs()
        if (!prefs.getBoolean(SmartPrefs.CLIP_CHIP, SmartPrefs.DEF_CLIP_CHIP)) return null
        if (dontShowCurrentSuggestion) return null
        if (parent == null) return null
        if (latinIME.mSettings.current.mIncognitoModeEnabled || latinIME.mSettings.current.mInputAttributes.mNoLearning ||
            InputTypeUtils.isPasswordInputType(editorInfo?.inputType ?: 0)) return null
        val primary = clipboardManager.primaryClip
        val primaryTime = primary?.let(ClipboardManagerCompat::getClipTimestamp) ?: 0L
        val windowMillis = prefs.getInt(SmartPrefs.CLIP_CHIP_MINUTES, SmartPrefs.DEF_CLIP_CHIP_MINUTES) * 60_000L
        val screenshot = if (prefs.getBoolean(helium314.keyboard.smart.clipboard.ScreenshotHistory.ENABLED, false))
            clipboardDao?.getAll()?.filter { it.filename != null && it.text == helium314.keyboard.smart.clipboard.ScreenshotHistory.LABEL &&
                it.timeStamp > primaryTime && System.currentTimeMillis() - it.timeStamp in 0..windowMillis }
                ?.maxByOrNull { it.timeStamp }
        else null
        val clipData = screenshot?.let {
            ClipData(ClipDescription(it.text, it.mimeTypes?.toTypedArray() ?: arrayOf("image/png")), ClipData.Item(it.getContentUri(latinIME)))
        } ?: primary ?: return null
        if (clipData.itemCount == 0) return null
        val clipItem = clipData.getItemAt(0) ?: return null
        val hasText = clipData.description?.hasMimeType("image/*") != true && clipData.description?.hasMimeType("text/*") == true
        val hasImage = clipData.description?.hasMimeType("image/*") == true && clipItem.uri != null
        if (!hasText && !hasImage) return null
        val timeStamp = screenshot?.timeStamp ?: primaryTime
        if (System.currentTimeMillis() - timeStamp > windowMillis) return null
        val content = clipItem.coerceToText(latinIME)

        // create the view
        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(latinIME), parent, false)
        val textView = binding.clipboardSuggestionText
        val clipIcon = KeyboardIconsSet.instance.getIconDrawable(ToolbarKey.PASTE.name.lowercase())
        clipIcon?.setBounds(0, 0, textView.lineHeight, textView.lineHeight) // scale the icon to the text
        textView.setCompoundDrawablesRelative(clipIcon, null, null, null)
        val inputType = editorInfo?.inputType ?: InputType.TYPE_NULL
        if (hasText) {
            if (TextUtils.isEmpty(content)) return null
            if (InputTypeUtils.isNumberInputType(inputType) && !content.isValidNumber()) return null
            val shortenedContent = content.take(200) // for display only
            KeyboardTypeface.applyToTextView(textView)
            textView.text = (if (isClipSensitive(inputType)) "*".repeat(shortenedContent.length) else shortenedContent)
        }
        val storedImage = screenshot ?: if (hasImage) clipboardDao?.getAll()?.firstOrNull { it.timeStamp == timeStamp && it.filename != null } else null
        val onClickListener = View.OnClickListener {
            dontShowCurrentSuggestion = true
            if (hasText) latinIME.onTextInput(content.toString())
            else {
                val image = clipboardDao?.getAll()?.firstOrNull { it.timeStamp == timeStamp && it.filename != null }
                if (image != null) latinIME.mKeyboardActionListener.onContent(image.getContentInfo(latinIME))
                else latinIME.onCodeInput(KeyCode.CLIPBOARD, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            }
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS)
            binding.root.isGone = true
        }
        textView.setOnClickListener(onClickListener)

        if (hasImage) {
            if (InputTypeUtils.isNumberInputType(inputType)) return null
            val imageView = binding.clipboardSuggestionImage
            // a real, small thumbnail of the copied picture that fits into the strip height
            val stripHeight = latinIME.resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height)
            // the chip background is inset by 5dp, keep the picture inside it
            val density = latinIME.resources.displayMetrics.density
            val thumbHeight = (stripHeight - 18 * density).toInt().coerceAtLeast((16 * density).toInt())
            val bitmap = storedImage?.let { SmartClipboard.decodeFile(java.io.File(ClipboardDao.clipFilesDir, it.filename!!), thumbHeight * 3) }
                ?: SmartClipboard.decodeUri(latinIME, clipItem.uri, thumbHeight * 3)
                ?: return null // e.g. SecurityException: Permission Denial
            imageView.isVisible = true
            imageView.setImageBitmap(bitmap)
            imageView.adjustViewBounds = false
            imageView.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            imageView.setPadding(0, 0, 0, 0)
            imageView.layoutParams = (imageView.layoutParams as ViewGroup.MarginLayoutParams).apply {
                height = thumbHeight
                width = (thumbHeight * (bitmap.width.toFloat() / bitmap.height).coerceIn(0.75f, 1.8f)).toInt()
                marginEnd = (4 * density).toInt()
            }
            imageView.clipToOutline = true
            imageView.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, thumbHeight * 0.2f)
            }
            imageView.contentDescription = latinIME.getString(R.string.sv_clip_image)
            imageView.setOnClickListener(onClickListener)
            if (!hasText) {
                textView.text = if (screenshot != null) helium314.keyboard.smart.clipboard.ScreenshotHistory.LABEL else null
                textView.setPadding(textView.paddingStart, 0, 0, 0)
                textView.layoutParams = (textView.layoutParams as android.widget.LinearLayout.LayoutParams).apply { width = ViewGroup.LayoutParams.WRAP_CONTENT; weight = 0f }
            }
        }

        // no close button: the chip disappears after pasting or when typing starts
        binding.clipboardSuggestionClose.isGone = true

        val colors = latinIME.mSettings.current.mColors
        textView.setTextColor(colors.get(ColorType.KEY_TEXT))
        clipIcon?.let { colors.setColor(it, ColorType.CLIPBOARD_SUGGESTION_ICON) }
        colors.setBackground(binding.root, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)

        clipboardSuggestionView = binding.root
        return clipboardSuggestionView
    }

    private fun removeClipboardSuggestion() {
        dontShowCurrentSuggestion = true
        val csv = clipboardSuggestionView ?: return
        if (csv.parent != null && !csv.isGone) {
            // clipboard view is shown ->
            latinIME.setNeutralSuggestionStrip()
            latinIME.mHandler.postResumeSuggestions(false)
        }
        csv.isGone = true
    }

    companion object {
        private val TAG = "ClipboardHistoryManager"

        // avoid showing the current suggestion because it has been dismissed or pasted
        private var dontShowCurrentSuggestion: Boolean = false

        const val RECENT_TIME_MILLIS = 3 * 60 * 1000L // 3 minutes (for clipboard suggestions)

        private fun maySaveFromUri(uri: Uri?, context: Context): Boolean {
            val maxSize = context.prefs().getInt(Settings.PREF_CLIPBOARD_FILES_SIZE_LIMIT, Defaults.PREF_CLIPBOARD_FILES_SIZE_LIMIT)
            val saveUriData = context.prefs().getBoolean(Settings.PREF_CLIPBOARD_USE_FILES, Defaults.PREF_CLIPBOARD_USE_FILES)
            if (uri == null || !saveUriData) return false
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null).use {
                    if (it?.moveToFirst() != true) return false
                    val size = it.getLong(0)
                    return size <= maxSize * 1000000 // maxSize is megabytes
                }
            } catch (e: Exception) {
                Log.w(TAG, "error checking clip size", e) // happens with SecurityException: Permission Denial
                return false
            }
        }
    }
}
