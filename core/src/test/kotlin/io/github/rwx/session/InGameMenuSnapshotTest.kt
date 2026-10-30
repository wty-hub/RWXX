package io.github.rwx.session

import com.corrodinggames.rts.gameFramework.statistics.AndroidMenu
import io.github.rwx.i18n.I18n
import io.github.rwx.ui.model.PauseMenuAction
import io.github.rwx.ui.model.PauseMenuNavigation
import io.github.rwx.ui.model.PauseMenuOutcome
import io.github.rwx.ui.model.PauseMenuViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InGameMenuSnapshotTest {
    @Test
    fun `mod overrides keep native ID callback priority and labels on all matching menu rows`() {
        val menu = AndroidMenu()
        menu.add(0, 12, 0, "Save Game")
        menu.add(0, 9, 0, "Stop Recording")
        menu.add(0, 18, 0, "Export Map")
        menu.add(0, 12, 0, "My custom save")
        menu.add(0, 9, 0, "Custom record")

        val snapshot = menu.snapshotOptions(setOf(12, 9))
        assertEquals(listOf(true, true, false, true, true), snapshot.map { it.isModOverride })

        val items = PauseMenuViewModel.items(snapshot).drop(1)
        val overridden = items.filterIndexed { index, _ -> snapshot[index].isModOverride }
        assertEquals(listOf("Save Game", "Stop Recording", "My custom save", "Custom record"), overridden.map { it.label })
        assertEquals(
            listOf(PauseMenuAction.Legacy(12), PauseMenuAction.Legacy(9), PauseMenuAction.Legacy(12), PauseMenuAction.Legacy(9)),
            overridden.map { it.action },
        )
        for (item in overridden) {
            val action = item.action as PauseMenuAction.Legacy
            assertEquals(PauseMenuOutcome.SelectLegacyOption(action.menuId), PauseMenuNavigation.outcomeFor(action))
        }
        assertEquals(I18n.ingame.exportMap(), items[2].label)
    }

    @Test
    fun `snapshot copies mutable engine menu labels before passing them to the UI`() {
        val menu = AndroidMenu()
        val title = StringBuilder("模组菜单")
        menu.add(0, 5001, 0, title)
        val snapshot = menu.snapshotOptions()

        title.append("（已更新）")
        menu.getItem(0).setTitle("新菜单")
        menu.clear()

        assertEquals(listOf(InGameMenuOptionSnapshot(5001, "模组菜单")), snapshot)
        assertFailsWith<UnsupportedOperationException> {
            (snapshot as MutableList<InGameMenuOptionSnapshot>).clear()
        }
    }

    @Test
    fun `new pause panel preserves extra native and mod options in native order`() {
        val options = listOf(
            InGameMenuOptionSnapshot(12, "保存游戏"),
            InGameMenuOptionSnapshot(18, "导出地图"),
            InGameMenuOptionSnapshot(2, "设置"),
            InGameMenuOptionSnapshot(22, "隐藏界面"),
            InGameMenuOptionSnapshot(11, "任务简报"),
            InGameMenuOptionSnapshot(24, "地图列表"),
            InGameMenuOptionSnapshot(5001, "My mod menu"),
            InGameMenuOptionSnapshot(9, "开始录制"),
            InGameMenuOptionSnapshot(15, "退出游戏"),
        )
        val items = PauseMenuViewModel.items(options)

        assertEquals(PauseMenuAction.Resume, items.first().action)
        assertEquals("My mod menu", items.single { it.action == PauseMenuAction.Legacy(5001) }.label)
        assertEquals(
            listOf(
                PauseMenuAction.Save,
                PauseMenuAction.Legacy(18),
                PauseMenuAction.Settings,
                PauseMenuAction.Legacy(22),
                PauseMenuAction.Legacy(11),
                PauseMenuAction.Legacy(24),
                PauseMenuAction.Legacy(5001),
                PauseMenuAction.Legacy(9),
                PauseMenuAction.ExitGame,
            ),
            items.drop(1).map { it.action },
        )
        for (item in items) {
            val action = item.action as? PauseMenuAction.Legacy ?: continue
            assertEquals(PauseMenuOutcome.SelectLegacyOption(action.menuId), PauseMenuNavigation.outcomeFor(action))
        }
        assertFalse(items.any { it.action == PauseMenuAction.Surrender })
    }

    @Test
    fun `native English map and recording labels follow the UI language while mod names stay unchanged`() {
        val previous = I18n.currentLocale
        try {
            I18n.setLocale(java.util.Locale.SIMPLIFIED_CHINESE)
            val options = listOf(
                InGameMenuOptionSnapshot(12, "Save Game"),
                InGameMenuOptionSnapshot(18, "Export Map"),
                InGameMenuOptionSnapshot(24, "RWXX Maps"),
                InGameMenuOptionSnapshot(5001, "My mod menu"),
                InGameMenuOptionSnapshot(9, "Start Recording"),
            )
            val items = PauseMenuViewModel.items(options)
            assertEquals(
                listOf("继续", "保存", "导出地图", "RWXX 地图", "My mod menu", "开始录制"),
                items.map { it.label },
            )
            assertEquals("停止录制", PauseMenuViewModel.items(listOf(InGameMenuOptionSnapshot(9, "Stop Recording"))).last().label)
        } finally {
            I18n.setLocale(previous)
        }
    }

    @Test
    fun `pause panel does not invent multiplayer actions that the native menu omitted`() {
        val options = listOf(
            InGameMenuOptionSnapshot(13, "聊天"),
            InGameMenuOptionSnapshot(14, "玩家"),
            InGameMenuOptionSnapshot(10, "断开连接"),
        )
        val withoutSurrender = PauseMenuViewModel.items(options)
        assertEquals(
            listOf(PauseMenuAction.Resume, PauseMenuAction.Chat, PauseMenuAction.Players, PauseMenuAction.ExitGame),
            withoutSurrender.map { it.action },
        )
        assertEquals("断开连接", withoutSurrender.last().label)
        assertFalse(withoutSurrender.any { it.action == PauseMenuAction.Surrender })
        assertTrue(PauseMenuViewModel.items(options + InGameMenuOptionSnapshot(19, "投降"))
            .any { it.action == PauseMenuAction.Surrender })
    }
}
