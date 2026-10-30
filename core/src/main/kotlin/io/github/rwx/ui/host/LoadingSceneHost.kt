package io.github.rwx.ui.host

import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.pipeline.ClearColorFill
import de.fabmax.kool.scene.Scene
import io.github.rwx.i18n.I18n
import io.github.rwx.session.GameLoadingStatus
import io.github.rwx.ui.*
import io.github.rwx.ui.component.*
import io.github.rwx.ui.model.SettingsModel

class LoadingSceneHost(
    private val model: SettingsModel = SettingsModel(),
) {
    private val label: MutableStateValue<String> = mutableStateOf(LOADING_LABEL)
    private val progress: MutableStateValue<Float> = mutableStateOf(LOADING_MIN_VISIBLE_PROGRESS)
    private val recentSteps: MutableStateValue<List<String>> = mutableStateOf(emptyList())
    private val warmupUiTextures: MutableStateValue<Boolean> = mutableStateOf(false)

    fun update(status: GameLoadingStatus) {
        val nextLabel = status.text.ifBlank { LOADING_LABEL }
        val nextProgress = (status.progress ?: LOADING_MIN_VISIBLE_PROGRESS)
            .coerceIn(0.0f, 1.0f)
            .coerceAtLeast(LOADING_MIN_VISIBLE_PROGRESS)
        val historyBase = if (nextProgress + 0.5f < progress.value) emptyList() else recentSteps.value
        val reportedSteps = status.recentSteps.filter { it.isNotBlank() }
        val newSteps = reportedSteps.filterNot { it in historyBase }
        recentSteps.value = (historyBase + newSteps + nextLabel)
            .fold(emptyList<String>()) { steps, step ->
                if (steps.lastOrNull() == step) steps else steps + step
            }
            .takeLast(LOADING_HISTORY_MAX_ROWS)
        label.value = nextLabel
        progress.value = nextProgress
    }

    fun showUiTextureWarmup() {
        warmupUiTextures.value = true
    }

    fun hideUiTextureWarmup() {
        warmupUiTextures.value = false
    }

    fun createScene(sceneName: String = LOADING_SCENE_NAME): Scene =
        createLoadingScene(model, sceneName, label, progress, recentSteps, warmupUiTextures)

    companion object {
        const val LOADING_SCENE_NAME: String = "loading-scene"
        const val GAME_LOADING_SCENE_NAME: String = "game-loading-scene"
        val LOADING_LABEL: String get() = I18n.loading.label()

        fun createScene(
            model: SettingsModel = SettingsModel(),
            sceneName: String = LOADING_SCENE_NAME,
            label: String = LOADING_LABEL,
        ): Scene =
            createLoadingScene(
                model = model,
                sceneName = sceneName,
                label = mutableStateOf(label.ifBlank { LOADING_LABEL }),
                progress = mutableStateOf(LOADING_MIN_VISIBLE_PROGRESS),
                recentSteps = mutableStateOf(emptyList()),
                warmupUiTextures = mutableStateOf(false),
            )
    }
}

private const val LOADING_MIN_VISIBLE_PROGRESS: Float = 0.02f
private val LoadingTextHeight: Dp = Dp(64f)
private val LoadingTextSpinnerSize: Dp = Dp(28f)
private val LoadingTextSpinnerStroke: Dp = Dp(3f)
private val LoadingHistoryHeight: Dp = Dp(136f)
private val LoadingHistoryViewportHeight: Dp = Dp(96f)
private val LoadingHistoryRowHeight: Dp = Dp(24f)
private const val LOADING_HISTORY_MAX_ROWS: Int = 32

private fun createLoadingScene(
    model: SettingsModel,
    sceneName: String,
    label: MutableStateValue<String>,
    progress: MutableStateValue<Float>,
    recentSteps: MutableStateValue<List<String>>,
    warmupUiTextures: MutableStateValue<Boolean>,
): Scene {
    val theme = ColorSchemeRegistry.cyberpunkMenuScheme
    return UiScene(sceneName, clearColor = ClearColorFill(theme.palette.surfaceBase)) {
        addPanelSurface(PanelStyle.Menu, "loading-panel", model) { activeTheme ->
            val contentWidth = loadingContentWidth()
            MainMenuHeader(contentWidth, activeTheme)
            LoadingStatus(label.use(), progress.use(), recentSteps.use(), activeTheme, contentWidth)
            Box(width = Dp(1f), height = Dp(1f)) {
                if (warmupUiTextures.use()) UiIconWarmup(activeTheme)
            }
        }
    }
}

private fun UiScope.LoadingStatus(
    text: String,
    progress: Float,
    recentSteps: List<String>,
    theme: ColorSchemeDefinition,
    contentWidth: Dp,
) {
    val statusWidth = contentWidth.fraction(0.72f, minWidth = Dp(240f), maxWidth = Dp(560f))
    Box(width = statusWidth, height = LoadingTextHeight) {
        modifier
            .margin(bottom = UiTheme.Spacing.md)
            .alignX(AlignmentX.Center)

        Row(width = Grow.Std, height = Grow.Std) {
            modifier.align(AlignmentX.Start, AlignmentY.Center)

            Box(width = Dp(42f), height = Grow.Std) {
                CircularLoadingIndicator(
                    size = LoadingTextSpinnerSize,
                    strokeWidth = LoadingTextSpinnerStroke,
                    theme = theme,
                ).modifier.align(AlignmentX.Start, AlignmentY.Center)
            }

            Text(text) {
                modifier
                    .width(Grow.Std)
                    .height(Grow.Std)
                    .font(if (text.all { it.code in 32..126 }) {
                        UiTheme.Fonts.titleBase.derive(17f)
                    } else {
                        UiTheme.Fonts.bodySmall
                    })
                    .textAlign(AlignmentX.Start, AlignmentY.Center)
                    .isWrapText(false)
                    .clipToBounds(true)
                    .textColor(theme.palette.textPrimary)
            }
        }
    }
    LoadingProgressBar(
        progress = progress,
        width = statusWidth,
        theme = theme,
    )
    LoadingStepHistory(recentSteps, statusWidth, theme)
}

private fun UiScope.LoadingStepHistory(
    recentSteps: List<String>,
    width: Dp,
    theme: ColorSchemeDefinition,
) {
    Box(width = width, height = LoadingHistoryHeight) {
        modifier
            .alignX(AlignmentX.Center)
            .margin(top = UiTheme.Spacing.md)
            .padding(UiTheme.Spacing.sm)
            .background(RoundRectBackground(theme.palette.surfaceSunken, theme.smallCornerRadius))
            .border(RoundRectBorder(theme.palette.borderSubtle, theme.smallCornerRadius, Dp(1f)))

        Column(width = Grow.Std, height = Grow.Std) {
            Row(width = Grow.Std, height = Dp(24f)) {
                Text(I18n.loading.eventLog()) {
                    modifier
                        .width(Grow.Std)
                        .font(UiTheme.Fonts.base.derive(13f))
                        .textColor(theme.palette.primary)
                }
                Text(I18n.loading.autoScroll()) {
                    modifier
                        .font(UiTheme.Fonts.base.derive(11f))
                        .textColor(theme.palette.secondary)
                }
            }
            Box(width = Grow.Std, height = LoadingHistoryViewportHeight) {
                modifier.background(RoundRectBackground(theme.palette.surfaceBase, theme.smallCornerRadius))
                StickToEndScrollColumn(
                    items = recentSteps.mapIndexed { index, step -> LoadingLogLine(index, step) },
                    theme = theme,
                    width = Grow.Std,
                    height = Grow.Std,
                    smoothFollow = true,
                ) { line ->
                    Row(width = Grow.Std, height = LoadingHistoryRowHeight) {
                        modifier.padding(horizontal = UiTheme.Spacing.xs)
                        Text(line.index.toString().padStart(2, '0')) {
                            modifier
                                .width(Dp(32f))
                                .height(Grow.Std)
                                .font(UiTheme.Fonts.titleBase.derive(12f))
                                .textAlign(AlignmentX.Start, AlignmentY.Center)
                                .textColor(theme.palette.secondary)
                        }
                        Text(line.text) {
                            modifier
                                .width(Grow.Std)
                                .height(Grow.Std)
                                .font(if (line.text.all { it.code in 32..126 }) {
                                    UiTheme.Fonts.titleBase.derive(14f)
                                } else {
                                    UiTheme.Fonts.base.derive(16f)
                                })
                                .textAlign(AlignmentX.Start, AlignmentY.Center)
                                .isWrapText(false)
                                .clipToBounds(true)
                                .textColor(theme.palette.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

private data class LoadingLogLine(val index: Int, val text: String)

private fun UiScope.loadingContentWidth(): Dp =
    ResponsiveContentWidth(
        defaultWidth = Dp(620f),
        minWidth = Dp(280f),
        maxWidth = Dp(760f),
        horizontalMargin = Dp(64f),
    )
