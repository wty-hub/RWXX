package io.github.rwx.app

import io.github.rwx.i18n.I18n
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomTextLocalizationTest {
    @Test
    fun `legacy status translates fixed fields and preserves custom content`() {
        val originalLocale = I18n.currentLocale
        try {
            I18n.setLocale(Locale.SIMPLIFIED_CHINESE)
            assertEquals(
                listOf("初始资金：默认（$4000）", "战争迷雾：基础迷雾", "初始单位：3 名工程师", "custom mod message"),
                originalBattleRoomStatusLines(
                    "Game Mode: Custom Map\nMap: sample.tmx\nStarting Credits: Default ($4000)\nFog: Basic fog\nStarting Units: 3 Engineers\ncustom mod message",
                ),
            )
            assertEquals("局域网地址：192.168.0.1，端口：5123", localizeLegacyRoomLine("Local IP address: 192.168.0.1 port: 5123"))
            assertEquals("模组：custom unit", localizeLegacyRoomLine("mod: custom unit"))
            I18n.setLocale(Locale.ENGLISH)
            assertEquals("mod: custom unit", localizeLegacyRoomLine("mod: custom unit"))
        } finally {
            I18n.setLocale(originalLocale)
        }
    }
}
