package io.github.ynotbili.dfmnext.danmaku.model.android

import io.github.ynotbili.dfmnext.controller.DanmakuFilters
import io.github.ynotbili.dfmnext.controller.DanmakuFilters.IDanmakuFilter
import io.github.ynotbili.dfmnext.danmaku.model.AbsDisplayer
import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.GlobalFlagValues

class DanmakuContext {

    companion object {
        fun create(): DanmakuContext = DanmakuContext()
    }

    enum class DanmakuConfigTag {
        FT_DANMAKU_VISIBILITY, FB_DANMAKU_VISIBILITY, L2R_DANMAKU_VISIBILITY, R2L_DANMAKU_VISIBILITY,
        SPECIAL_DANMAKU_VISIBILITY, TRANSPARENCY, SCALE_TEXTSIZE,
        DUPLICATE_MERGING_ENABLED, MAXIMUM_LINES, OVERLAPPING_ENABLE;

        fun isVisibilityRelatedTag(): Boolean {
            return this == FT_DANMAKU_VISIBILITY || this == FB_DANMAKU_VISIBILITY ||
                this == L2R_DANMAKU_VISIBILITY || this == R2L_DANMAKU_VISIBILITY ||
                this == SPECIAL_DANMAKU_VISIBILITY
        }
    }

    var transparency: Int = BaseDanmaku.ALPHA_MAX
    var scaleTextSize: Float = 1.0f

    /**
     * Per-type visibility toggles. They are stored in [mFilterTypes] (the hidden
     * set) rather than as five fields, so there is a single source of truth for
     * the filter and no `KMutableProperty0` reflection hop per toggle.
     */
    var FTDanmakuVisibility: Boolean
        get() = isTypeVisible(BaseDanmaku.TYPE_FIX_TOP)
        set(visible) = setVisibilityAndUpdate(visible, BaseDanmaku.TYPE_FIX_TOP, DanmakuConfigTag.FT_DANMAKU_VISIBILITY)
    var FBDanmakuVisibility: Boolean
        get() = isTypeVisible(BaseDanmaku.TYPE_FIX_BOTTOM)
        set(visible) = setVisibilityAndUpdate(visible, BaseDanmaku.TYPE_FIX_BOTTOM, DanmakuConfigTag.FB_DANMAKU_VISIBILITY)
    var L2RDanmakuVisibility: Boolean
        get() = isTypeVisible(BaseDanmaku.TYPE_SCROLL_LR)
        set(visible) = setVisibilityAndUpdate(visible, BaseDanmaku.TYPE_SCROLL_LR, DanmakuConfigTag.L2R_DANMAKU_VISIBILITY)
    var R2LDanmakuVisibility: Boolean
        get() = isTypeVisible(BaseDanmaku.TYPE_SCROLL_RL)
        set(visible) = setVisibilityAndUpdate(visible, BaseDanmaku.TYPE_SCROLL_RL, DanmakuConfigTag.R2L_DANMAKU_VISIBILITY)
    var SpecialDanmakuVisibility: Boolean
        get() = isTypeVisible(BaseDanmaku.TYPE_SPECIAL)
        set(visible) = setVisibilityAndUpdate(visible, BaseDanmaku.TYPE_SPECIAL, DanmakuConfigTag.SPECIAL_DANMAKU_VISIBILITY)

    val mFilterTypes: MutableList<Int> = ArrayList()
    var refreshRateMS: Int = 15

    private var configChangedCallback: ConfigChangedCallback? = null
    private var mDuplicateMergingEnable: Boolean = false
    private var mIsMaxLinesLimited: Boolean = false
    private var mIsPreventOverlappingEnabled: Boolean = false

    private val mDisplayer: AbsDisplayer by lazy { AndroidDisplayer() }

    val mGlobalFlagValues: GlobalFlagValues = GlobalFlagValues()
    val mDanmakuFilters: DanmakuFilters = DanmakuFilters()
    val mDanmakuFactory: DanmakuFactory = DanmakuFactory.create()

    fun getDisplayer(): AbsDisplayer = mDisplayer

    fun setDanmakuTransparency(p: Float): DanmakuContext {
        val newTransparency = (p * BaseDanmaku.ALPHA_MAX).toInt()
        if (newTransparency != transparency) {
            transparency = newTransparency
            mDisplayer.setTransparency(newTransparency)
            notifyConfigureChanged(DanmakuConfigTag.TRANSPARENCY, p)
        }
        return this
    }

    fun setScaleTextSize(p: Float): DanmakuContext {
        if (scaleTextSize != p) {
            scaleTextSize = p
            mDisplayer.clearTextHeightCache()
            mDisplayer.setScaleTextSizeFactor(p)
            mGlobalFlagValues.updateMeasureFlag()
            mGlobalFlagValues.updateVisibleFlag()
            notifyConfigureChanged(DanmakuConfigTag.SCALE_TEXTSIZE, p)
        }
        return this
    }

    fun setFTDanmakuVisibility(visible: Boolean): DanmakuContext {
        FTDanmakuVisibility = visible
        return this
    }

    fun setFBDanmakuVisibility(visible: Boolean): DanmakuContext {
        FBDanmakuVisibility = visible
        return this
    }

    fun setL2RDanmakuVisibility(visible: Boolean): DanmakuContext {
        L2RDanmakuVisibility = visible
        return this
    }

    fun setR2LDanmakuVisibility(visible: Boolean): DanmakuContext {
        R2LDanmakuVisibility = visible
        return this
    }

    fun setSpecialDanmakuVisibility(visible: Boolean): DanmakuContext {
        SpecialDanmakuVisibility = visible
        return this
    }

    fun setDuplicateMergingEnabled(enable: Boolean): DanmakuContext {
        if (mDuplicateMergingEnable != enable) {
            mDuplicateMergingEnable = enable
            mGlobalFlagValues.updateFilterFlag()
            notifyConfigureChanged(DanmakuConfigTag.DUPLICATE_MERGING_ENABLED, enable)
        }
        return this
    }

    fun isDuplicateMergingEnabled(): Boolean = mDuplicateMergingEnable

    fun setMaximumLines(pairs: Map<Int, Int>?): DanmakuContext {
        mIsMaxLinesLimited = (pairs != null)
        if (pairs == null) {
            mDanmakuFilters.unregisterFilter(DanmakuFilters.TAG_MAXIMUN_LINES_FILTER, false)
        } else {
            setFilterData(DanmakuFilters.TAG_MAXIMUN_LINES_FILTER, pairs, false)
        }
        mGlobalFlagValues.updateFilterFlag()
        notifyConfigureChanged(DanmakuConfigTag.MAXIMUM_LINES, pairs)
        return this
    }

    @Deprecated("Use preventOverlapping", ReplaceWith("preventOverlapping(pairs)"))
    fun setOverlapping(pairs: Map<Int, Boolean>?): DanmakuContext = preventOverlapping(pairs)

    fun preventOverlapping(pairs: Map<Int, Boolean>?): DanmakuContext {
        mIsPreventOverlappingEnabled = (pairs != null)
        if (pairs == null) {
            mDanmakuFilters.unregisterFilter(DanmakuFilters.TAG_OVERLAPPING_FILTER, false)
        } else {
            setFilterData(DanmakuFilters.TAG_OVERLAPPING_FILTER, pairs, false)
        }
        mGlobalFlagValues.updateFilterFlag()
        notifyConfigureChanged(DanmakuConfigTag.OVERLAPPING_ENABLE, pairs)
        return this
    }

    fun isMaxLinesLimited(): Boolean = mIsMaxLinesLimited
    fun isPreventOverlappingEnabled(): Boolean = mIsPreventOverlappingEnabled

    interface ConfigChangedCallback {
        fun onDanmakuConfigChanged(config: DanmakuContext, tag: DanmakuConfigTag, vararg value: Any?): Boolean
    }

    fun registerConfigChangedCallback(listener: ConfigChangedCallback) {
        configChangedCallback = listener
    }

    fun unregisterConfigChangedCallback(listener: ConfigChangedCallback) {
        if (configChangedCallback == listener) configChangedCallback = null
    }

    fun unregisterAllConfigChangedCallbacks() {
        configChangedCallback = null
    }

    private fun isTypeVisible(type: Int): Boolean = !mFilterTypes.contains(type)

    /**
     * [mFilterTypes] holds the *hidden* types, so it doubles as the storage for the
     * visibility properties. The previous version kept a mirrored boolean per type
     * and passed `::field` around as a [kotlin.reflect.KMutableProperty0] — one
     * reflective call per toggle and two sources of truth that could disagree.
     */
    private fun setVisibilityAndUpdate(visible: Boolean, type: Int, tag: DanmakuConfigTag) {
        val wasVisible = isTypeVisible(type)
        setDanmakuVisible(visible, type)
        setFilterData(DanmakuFilters.TAG_TYPE_DANMAKU_FILTER, mFilterTypes)
        mGlobalFlagValues.updateFilterFlag()
        if (wasVisible != visible) {
            notifyConfigureChanged(tag, visible)
        }
    }

    private fun <T> setFilterData(tag: String, data: T) {
        setFilterData(tag, data, true)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> setFilterData(tag: String, data: T, primary: Boolean) {
        val filter = mDanmakuFilters.get(tag, primary) as IDanmakuFilter<T>
        filter.setData(data)
    }

    private fun setDanmakuVisible(visible: Boolean, type: Int) {
        if (visible) {
            mFilterTypes.remove(type)
        } else if (!mFilterTypes.contains(type)) {
            mFilterTypes.add(type)
        }
    }

    private fun notifyConfigureChanged(tag: DanmakuConfigTag, vararg values: Any?) {
        configChangedCallback?.onDanmakuConfigChanged(this, tag, *values)
    }
}
