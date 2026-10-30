package io.github.rwx.ui.component

import de.fabmax.kool.modules.ui2.*
import io.github.rwx.i18n.I18n
import io.github.rwx.ui.ColorSchemeDefinition
import io.github.rwx.ui.UiAppearance
import io.github.rwx.ui.UiTheme

private val CYBER_CAPTION_TOP_INSET: Dp = Dp(15f)
private val CYBER_CAPTION_BOTTOM_INSET: Dp = Dp(13f)

/**
 * Vertical band a panel has to keep free above and below its content column, otherwise the captions
 * below end up underneath buttons and list rows. Hosts add it to their vertical chrome when they
 * size list viewports.
 */
internal val CyberBackdropCaptionBand: Dp = Dp(
    maxOf(CYBER_CAPTION_TOP_INSET.value, CYBER_CAPTION_BOTTOM_INSET.value) +
        CYBER_CAPTION_LINE_HEIGHT_DP + CYBER_CAPTION_CLEARANCE_DP
)

/** Captions only fit on surfaces wide and tall enough to carry a readable frame band. */
internal fun UiScope.showsCyberBackdropCaptions(): Boolean {
    val width = Dp.fromPx(surface.viewportWidth.use()).value
    val height = Dp.fromPx(surface.viewportHeight.use()).value
    return width >= CYBER_CAPTION_MIN_VIEWPORT_WIDTH_DP && height >= CYBER_CAPTION_MIN_VIEWPORT_HEIGHT_DP
}

/** Quiet decorative layer behind menu content. Every element is non-interactive. */
fun UiScope.CyberBackdrop(theme: ColorSchemeDefinition, showLabels: Boolean = true) {
    val width = Dp.fromPx(surface.viewportWidth.use()).value
    val height = Dp.fromPx(surface.viewportHeight.use()).value
    val gridColor = theme.palette.primary.withAlpha(0.045f)
    val edgeColor = theme.palette.primary.withAlpha(0.68f)
    val ghostText = theme.palette.textSecondary.withAlpha(0.52f)

    repeat((width / 72f).toInt().coerceAtMost(32)) { index ->
        Box(width = Dp(1f), height = Grow.Std) {
            modifier.alignX(AlignmentX.Start)
                .margin(start = Dp((index + 1) * 72f))
                .backgroundColor(gridColor)
        }
    }
    repeat((height / 72f).toInt().coerceAtMost(20)) { index ->
        Box(width = Grow.Std, height = Dp(1f)) {
            modifier.alignY(AlignmentY.Top)
                .margin(top = Dp((index + 1) * 72f))
                .backgroundColor(gridColor)
        }
    }

    // Corner brackets and segmented status rails make the scene feel like one instrument panel.
    Box(width = Dp(46f), height = Dp(2f)) {
        modifier.align(AlignmentX.Start, AlignmentY.Top)
            .margin(start = Dp(20f), top = Dp(20f)).backgroundColor(edgeColor)
    }
    Box(width = Dp(2f), height = Dp(34f)) {
        modifier.align(AlignmentX.Start, AlignmentY.Top)
            .margin(start = Dp(20f), top = Dp(20f)).backgroundColor(edgeColor)
    }
    Box(width = Dp(46f), height = Dp(2f)) {
        modifier.align(AlignmentX.End, AlignmentY.Top)
            .margin(end = Dp(20f), top = Dp(20f)).backgroundColor(edgeColor)
    }
    Box(width = Dp(2f), height = Dp(34f)) {
        modifier.align(AlignmentX.End, AlignmentY.Top)
            .margin(end = Dp(20f), top = Dp(20f)).backgroundColor(edgeColor)
    }
    Box(width = Dp(46f), height = Dp(2f)) {
        modifier.align(AlignmentX.Start, AlignmentY.Bottom)
            .margin(start = Dp(20f), bottom = Dp(20f)).backgroundColor(edgeColor)
    }
    Box(width = Dp(2f), height = Dp(34f)) {
        modifier.align(AlignmentX.Start, AlignmentY.Bottom)
            .margin(start = Dp(20f), bottom = Dp(20f)).backgroundColor(edgeColor)
    }
    Box(width = Dp(46f), height = Dp(2f)) {
        modifier.align(AlignmentX.End, AlignmentY.Bottom)
            .margin(end = Dp(20f), bottom = Dp(20f)).backgroundColor(edgeColor)
    }
    Box(width = Dp(2f), height = Dp(34f)) {
        modifier.align(AlignmentX.End, AlignmentY.Bottom)
            .margin(end = Dp(20f), bottom = Dp(20f)).backgroundColor(edgeColor)
    }

    if (showLabels && showsCyberBackdropCaptions()) {
        Text(I18n.interfaceChrome.tacticalInterface()) {
            modifier.align(AlignmentX.Start, AlignmentY.Top)
                .margin(start = Dp(78f), top = CYBER_CAPTION_TOP_INSET)
                .font(UiTheme.Fonts.caption).textColor(ghostText)
        }
        Text(I18n.interfaceChrome.system()) {
            modifier.align(AlignmentX.End, AlignmentY.Top)
                .margin(end = Dp(78f), top = CYBER_CAPTION_TOP_INSET)
                .font(UiTheme.Fonts.caption).textColor(ghostText)
        }
        Text(I18n.interfaceChrome.gameName()) {
            modifier.align(AlignmentX.Start, AlignmentY.Bottom)
                .margin(start = Dp(78f), bottom = CYBER_CAPTION_BOTTOM_INSET)
                .font(UiTheme.Fonts.caption).textColor(ghostText)
        }
        Row(width = FitContent, height = Dp(4f)) {
            modifier.align(AlignmentX.End, AlignmentY.Bottom)
                .margin(end = Dp(80f), bottom = Dp(22f))
            repeat(8) { index ->
                Box(width = Dp(if (index % 3 == 0) 20f else 9f), height = Dp(2f)) {
                    modifier.margin(start = Dp(3f))
                        .backgroundColor(if (index == 7) theme.palette.secondary.withAlpha(0.7f) else edgeColor)
                }
            }
        }
    }
}

/** Small frame details for cards and image windows; classic controls render no extra chrome. */
fun UiScope.CyberCardRail(theme: ColorSchemeDefinition, active: Boolean = false) {
    if (theme.appearance != UiAppearance.Cyberpunk) return
    Box(width = Grow.Std, height = Dp(1f)) {
        modifier.alignY(AlignmentY.Bottom)
            .backgroundColor(theme.palette.primary.withAlpha(if (active) 0.9f else 0.32f))
    }
    Box(width = Dp(32f), height = Dp(2f)) {
        modifier.align(AlignmentX.End, AlignmentY.Top)
            .backgroundColor(theme.palette.secondary.withAlpha(if (active) 0.95f else 0.58f))
    }
    Box(width = Dp(2f), height = Dp(18f)) {
        modifier.align(AlignmentX.End, AlignmentY.Top)
            .backgroundColor(theme.palette.secondary.withAlpha(if (active) 0.95f else 0.58f))
    }
}

/** Caption font is 14dp with a 1.48 line-height factor, so a caption line is ~21dp tall. */
private const val CYBER_CAPTION_LINE_HEIGHT_DP: Float = 21f
private const val CYBER_CAPTION_CLEARANCE_DP: Float = 4f
private const val CYBER_CAPTION_MIN_VIEWPORT_WIDTH_DP: Float = 650f
private const val CYBER_CAPTION_MIN_VIEWPORT_HEIGHT_DP: Float = 480f
