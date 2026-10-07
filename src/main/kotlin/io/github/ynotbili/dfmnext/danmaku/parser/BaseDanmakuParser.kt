package io.github.ynotbili.dfmnext.danmaku.parser

import android.content.SharedPreferences
import io.github.ynotbili.dfmnext.danmaku.model.DanmakuTimer
import io.github.ynotbili.dfmnext.danmaku.model.IDanmakus
import io.github.ynotbili.dfmnext.danmaku.model.IDisplayer
import io.github.ynotbili.dfmnext.danmaku.model.android.DanmakuContext

abstract class BaseDanmakuParser {

    protected var mDataSource: IDataSource<*>? = null
    protected var mTimer: DanmakuTimer? = null
    protected var mDispWidth: Int = 0
    protected var mDispHeight: Int = 0
    protected var mDispDensity: Float = 0f
    protected var mScaledDensity: Float = 0f
    private var mDanmakus: IDanmakus? = null
    protected var mDisp: IDisplayer? = null
    protected lateinit var mContext: DanmakuContext

    /**
     * Renders advanced (animated/special) danmakus. Set directly, or loaded once
     * per parse from [sharedPreferences].
     */
    var advancedDanmakuEnabled: Boolean = true

    /** Rewrites every non-special danmaku as a right-to-left scroll. */
    var forceRightToLeftScroll: Boolean = false

    var sharedPreferences: SharedPreferences? = null

    open fun setDisplayer(disp: IDisplayer): BaseDanmakuParser {
        mDisp = disp
        mDispWidth = disp.width
        mDispHeight = disp.height
        mDispDensity = disp.density
        mScaledDensity = disp.scaledDensity
        mContext.mDanmakuFactory.updateViewportState(mDispWidth.toFloat(), mDispHeight.toFloat(), getViewportSizeFactor())
        mContext.mDanmakuFactory.updateMaxDanmakuDuration()
        return this
    }

    protected open fun getViewportSizeFactor(): Float {
        return 1f / (mDispDensity - 0.6f)
    }

    fun getDisplayer(): IDisplayer? = mDisp

    fun load(source: IDataSource<*>): BaseDanmakuParser {
        mDataSource = source
        return this
    }

    fun setTimer(timer: DanmakuTimer): BaseDanmakuParser {
        mTimer = timer
        return this
    }

    fun getTimer(): DanmakuTimer? = mTimer

    fun getDanmakus(): IDanmakus? {
        if (mDanmakus != null) return mDanmakus
        syncOptionsFromPreferences()
        mContext.mDanmakuFactory.resetDurationsData()
        mDanmakus = parse()
        releaseDataSource()
        mContext.mDanmakuFactory.updateMaxDanmakuDuration()
        return mDanmakus
    }

    /**
     * Resolves the two preference-backed switches once per parse. Reading them
     * inside the element loop meant two `SharedPreferences` lookups per danmaku,
     * i.e. tens of thousands of map probes and string keys for a typical file,
     * for two values that cannot change while parsing.
     */
    protected fun syncOptionsFromPreferences() {
        val prefs = sharedPreferences ?: return
        advancedDanmakuEnabled = prefs.getBoolean(KEY_ADVANCED_DANMAKU, true)
        forceRightToLeftScroll = prefs.getBoolean(KEY_FORCE_RTL, false)
    }

    /**
     * Mode rewrite implied by the options above: without advanced support, or
     * when the user asked for plain scrolling, everything becomes a
     * right-to-left scroll.
     */
    protected fun resolveMode(mode: Int): Int {
        if (mode == MODE_SPECIAL || mode == MODE_ADVANCED) {
            return if (advancedDanmakuEnabled) mode else MODE_SCROLL_RL
        }
        return if (forceRightToLeftScroll) MODE_SCROLL_RL else mode
    }

    protected fun isAdvancedPayload(content: String): Boolean =
        !advancedDanmakuEnabled && content.length > 1 &&
            content[0] == '[' && content[content.length - 1] == ']'

    protected fun releaseDataSource() {
        mDataSource?.release()
        mDataSource = null
    }

    protected abstract fun parse(): IDanmakus?

    fun release() {
        releaseDataSource()
    }

    fun setConfig(config: DanmakuContext): BaseDanmakuParser {
        if (::mContext.isInitialized && mContext !== config) {
            mDanmakus = null // call re-parse() under different context
        }
        mContext = config
        return this
    }

    companion object {
        private const val MODE_SCROLL_RL = 1
        private const val MODE_SPECIAL = 7
        private const val MODE_ADVANCED = 8

        const val KEY_ADVANCED_DANMAKU = "player_danmaku_advanced_enable"
        const val KEY_FORCE_RTL = "player_danmaku_forceR2L"
    }
}
