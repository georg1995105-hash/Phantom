package helium314.keyboard

import android.view.MotionEvent
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

// todo: expand with swipe & touch stuff
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowInputMethodService::class,
    ShadowLooper::class,
])
class InputTest {
    private val latinIME = Robolectric.setupService(LatinIME::class.java)
    private val keyboardSwitcher = KeyboardSwitcher.getInstance()
    private val originalSubtype = helium314.keyboard.latin.RichInputMethodManager.getInstance().currentSubtype.rawSubtype
    private val originalEnabled = latinIME.prefs().getString(helium314.keyboard.latin.settings.Settings.PREF_ENABLED_SUBTYPES, null)
    @org.junit.After fun restoreSubtype() {
        helium314.keyboard.latin.RichInputMethodManager.getInstance().onSubtypeChanged(originalSubtype)
        latinIME.prefs().edit().putString(helium314.keyboard.latin.settings.Settings.PREF_ENABLED_SUBTYPES, originalEnabled).commit()
        helium314.keyboard.latin.utils.SubtypeSettings.reloadEnabledSubtypes(latinIME)
    }
    init {
        ShadowLog.setupLogging()
        ShadowLog.stream = System.out
        // This suite asserts English letters; production defaults may be Russian.
        val english = helium314.keyboard.latin.utils.SubtypeSettings.getResourceSubtypesForLocale(java.util.Locale.US).first()
        helium314.keyboard.latin.utils.SubtypeSettings.addEnabledSubtype(latinIME.prefs(), english)
        latinIME.onCurrentInputMethodSubtypeChanged(english)
        // create keyboardView
        keyboardSwitcher.onCreateInputView(latinIME, true)
        // create and set keyboard
        keyboardSwitcher.reloadMainKeyboard()
    }

    @Test fun pressShift() {
        assertEquals(KeyboardElement.ALPHABET, keyboardSwitcher.keyboard?.mId?.element)
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN)
        assertEquals(KeyboardElement.ALPHABET_MANUAL_SHIFTED, keyboardSwitcher.keyboard?.mId?.element)
    }

    private fun withNumpad(mode: helium314.keyboard.smart.BottomNumpad.Mode, check: () -> Unit) {
        val prefs = latinIME.prefs()
        val saved = prefs.getString(helium314.keyboard.smart.SmartPrefs.BOTTOM_NUMPAD_MODE, null)
        val legacy = prefs.getBoolean(helium314.keyboard.smart.SmartPrefs.BOTTOM_NUMPAD, false)
        try {
            helium314.keyboard.smart.BottomNumpad.set(prefs, mode)
            helium314.keyboard.keyboard.KeyboardLayoutSet.onKeyboardThemeChanged()
            keyboardSwitcher.reloadMainKeyboard()
            check()
        } finally {
            prefs.edit().putString(helium314.keyboard.smart.SmartPrefs.BOTTOM_NUMPAD_MODE, saved)
                .putBoolean(helium314.keyboard.smart.SmartPrefs.BOTTOM_NUMPAD, legacy).commit()
            helium314.keyboard.keyboard.KeyboardLayoutSet.onKeyboardThemeChanged()
        }
    }

    @Test fun defaultBottomRowHasNoExtraNumpad() = withNumpad(helium314.keyboard.smart.BottomNumpad.Mode.OFF) {
        kotlin.test.assertNull(keyboardSwitcher.keyboard!!.getKey(KeyCode.NUMPAD))
        kotlin.test.assertNotNull(keyboardSwitcher.keyboard!!.getKey(KeyCode.SYMBOL_ALPHA))
    }

    @Test fun extraNumpadKeepsSymbolsAndSpace() = withNumpad(helium314.keyboard.smart.BottomNumpad.Mode.EXTRA) {
        kotlin.test.assertNotNull(keyboardSwitcher.keyboard!!.getKey(KeyCode.NUMPAD))
        kotlin.test.assertNotNull(keyboardSwitcher.keyboard!!.getKey(KeyCode.SYMBOL_ALPHA))
        kotlin.test.assertNotNull(keyboardSwitcher.keyboard!!.getKey(' '.code))
        latinIME.onCodeInput(KeyCode.NUMPAD, -1, -1, false)
        assertEquals(KeyboardElement.NUMPAD, keyboardSwitcher.keyboard!!.mId.element)
        latinIME.onCodeInput('7'.code, -1, -1, false)
        assertEquals("7", ShadowInputMethodService.text)
    }

    @Test fun numpadCanReplaceSymbolToggle() = withNumpad(helium314.keyboard.smart.BottomNumpad.Mode.REPLACE_SYMBOLS) {
        val keyboard = keyboardSwitcher.keyboard!!
        kotlin.test.assertNull(keyboard.getKey(KeyCode.SYMBOL_ALPHA))
        val numpad = kotlin.test.assertNotNull(keyboard.getKey(KeyCode.NUMPAD))
        kotlin.test.assertTrue(numpad.x < keyboard.mOccupiedWidth / 5)
        latinIME.onCodeInput(KeyCode.NUMPAD, -1, -1, false)
        assertEquals(KeyboardElement.NUMPAD, keyboardSwitcher.keyboard!!.mId.element)
        // The dedicated keypad exposes separate ABC and symbols actions.
        kotlin.test.assertNotNull(keyboardSwitcher.keyboard!!.getKey(KeyCode.ALPHA))
        kotlin.test.assertNotNull(keyboardSwitcher.keyboard!!.getKey(KeyCode.SYMBOL))
    }

    @Test fun keyInput() {
        touchKey('a'.code, MotionEvent.ACTION_DOWN)
        touchKey('a'.code, MotionEvent.ACTION_UP)
        assertEquals("a", ShadowInputMethodService.text)
    }

    @Test fun holdShift() {
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN)
        assertEquals(KeyboardElement.ALPHABET_MANUAL_SHIFTED, keyboardSwitcher.keyboard?.mId?.element)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks() // this doesn't actually wait, just results in handling the delayed message immediately
        assertEquals(KeyboardElement.ALPHABET_SHIFT_LOCKED, keyboardSwitcher.keyboard?.mId?.element)
    }

    @Test fun slidingInput() {
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN)
        touchKey('F'.code, MotionEvent.ACTION_MOVE)
        touchKey('F'.code, MotionEvent.ACTION_UP)
        assertEquals("F", ShadowInputMethodService.text)
        assertEquals(KeyboardElement.ALPHABET, keyboardSwitcher.keyboard?.mId?.element)
    }

    @Test fun endCapsLockWithShift() {
        // keyboardSwitcher.setAlphabetShiftLockedKeyboard() isn't enough because it doesn't set the fully correct state
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_UP)

        // need to set event time to prevent mTouchNoiseThresholdTime thing triggering (todo: should be done automatically in the test)
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN, 50L)
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_UP, 50L)
        assertEquals(KeyboardElement.ALPHABET, keyboardSwitcher.keyboard?.mId?.element)
    }

    @Test fun slidingInputFromCapsLock() {
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_UP)

        touchKey(KeyCode.SHIFT, MotionEvent.ACTION_DOWN, 50L)
        assertEquals(KeyboardElement.ALPHABET, keyboardSwitcher.keyboard?.mId?.element)
        touchKey('f'.code, MotionEvent.ACTION_MOVE)
        touchKey('f'.code, MotionEvent.ACTION_UP)
        assertEquals("f", ShadowInputMethodService.text)
        assertEquals(KeyboardElement.ALPHABET_SHIFT_LOCKED, keyboardSwitcher.keyboard?.mId?.element)
    }

    private fun touchKey(code: Int, action: Int, eventTime: Long = 0L) {
        val kb = keyboardSwitcher.keyboard!!
        val key = kb.getKey(code)!!
        val x = key.x + key.height / 2
        val y = key.y + key.width / 2
        val me = MotionEvent.obtain( // todo: there are many more parameters, also related to pointers
            0L,
            eventTime,
            action,
            x.toFloat(),
            y.toFloat(),
            0
        )
        keyboardSwitcher.mainKeyboardView.onTouchEvent(me)
    }

    @BeforeTest
    fun reset() {
        keyboardSwitcher.reloadMainKeyboard()
        ShadowInputMethodService.reset()
    }
}
