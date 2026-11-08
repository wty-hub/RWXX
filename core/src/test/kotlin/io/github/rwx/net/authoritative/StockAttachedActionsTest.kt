package io.github.rwx.net.authoritative

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.GameTeam
import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.custom.CustomUnit
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.game.units.custom.hooks.AttachmentSlotDefinition
import com.corrodinggames.rts.game.units.custom.logicBooleans.LogicBoolean
import com.corrodinggames.rts.game.units.custom.logicBooleans.LogicBooleanGameFunctions.HasFlagBoolean
import com.corrodinggames.rts.gameFramework.GameEngine
import java.util.IdentityHashMap
import kotlin.test.*

class StockAttachedActionsTest {
    private class Actor(config: CustomUnitConfig) : CustomUnit(true, config) {
        val children = IdentityHashMap<AttachmentSlotDefinition, OrderableUnit>()
        override fun a(config: CustomUnitConfig, initial: Boolean, convert: Boolean) { unitConfig = config; y = config.unitStats }
        override fun a(slot: AttachmentSlotDefinition): OrderableUnit? = children[slot]
    }

    private class Poison : LogicBoolean() {
        var reads = 0
        override fun read(unit: OrderableUnit): Boolean { reads++; error("Unreviewed condition was evaluated") }
        override fun getMatchFailReasonForPlayer(unit: OrderableUnit): String = error("Unreviewed explanation was evaluated")
    }

    private class World(val engine: GameLogic, val own: GameTeam, val enemy: GameTeam,
        val configs: Map<String, CustomUnitConfig>, val stock: StockUnitCatalog) {
        val proxy = StockAttachedActions(engine, stock)
        fun actor(key: String) = Actor(configs.getValue(key)).apply { team = own; buildProgress = 1f; currentHealth = 100f }
        val parent = actor("modularSpider")
        val child = actor("modularSpider_emptySlot")
        fun slot(index: Int = 1, position: Int = 0) = AttachmentSlotDefinition().apply {
            javaClass.getDeclaredField("a").apply { isAccessible = true }.setShort(this, position.toShort())
            javaClass.getDeclaredField("b").apply { isAccessible = true }.set(this, "unitSlot$index")
            N = HasFlagBoolean().apply { id(index) }; o = true
        }
        val slot = slot().also { bind(it) }
        fun bind(slot: AttachmentSlotDefinition) {
            parent.unitConfig.attachmentSlotDefinitions.clear()
            parent.unitConfig.attachmentSlotDefinitions.add(slot)
            parent.children.clear(); parent.children[slot] = child
            parent.unitFlags = 1 shl 1
            child.parentEntity = parent; child.attachmentData = slot
        }
    }

    private fun withWorld(block: World.() -> Unit) {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousEngine = instance.get(null)
        val previousPlatform = GameEngine.isNonAndroidVersion
        instance.set(null, null); GameEngine.isNonAndroidVersion = true
        val engine = GameLogic()
        val previousRegistry = CustomUnitConfig.activeConfigs
        val previousOverrides = HashMap(CustomUnitConfig.unitTypeOverrides)
        val teams = PlayerTeam::class.java.getDeclaredField("teamColorArray").apply { isAccessible = true }
        val previousTeams = teams.get(null) as Array<*>
        CustomUnitConfig.activeConfigs = arrayListOf(); CustomUnitConfig.unitTypeOverrides.clear()
        teams.set(null, arrayOfNulls<PlayerTeam>(previousTeams.size))
        try {
            val names = setOf("modularSpider", "modularSpider_nonEmpty", "modularSpider_emptySlot",
                "modularSpider_smallgunturret", "mechFactory")
            val configs = StockUnitManifest.bundled.filter { it.name in names }.associate { spec ->
                spec.name to CustomUnitConfig().apply {
                    name = spec.name; configPath = "assets/${spec.path}"; sourceFilePath = configPath
                    resourceLoadPath = configPath; configHash = spec.name.hashCode()
                }.also { CustomUnitConfig.activeConfigs.add(it) }
            }
            assertEquals(names, configs.keys)
            val own = GameTeam(0).apply { teamColorId = 0 }
            val enemy = GameTeam(1).apply { teamColorId = 1 }
            World(engine, own, enemy, configs, StockUnitCatalog.capture(engine)).block()
        } finally {
            teams.set(null, previousTeams)
            CustomUnitConfig.activeConfigs = previousRegistry
            CustomUnitConfig.unitTypeOverrides.clear(); CustomUnitConfig.unitTypeOverrides.putAll(previousOverrides)
            instance.set(null, previousEngine); GameEngine.isNonAndroidVersion = previousPlatform
        }
    }

    @Test fun `selected own stock slot exposes an operator without requiring child completion or selectability`() = withWorld {
        child.buildProgress = 0.25f
        child.unitConfig.isUnselectable = true
        val operator = proxy.selected(parent).single()
        assertSame(parent, operator.parent)
        assertSame(child, operator.actor)
        assertSame(slot, operator.slot)
        assertEquals(1, operator.slotIndex)
        assertTrue(proxy.current(operator))
        parent.unitFlags = 0
        assertTrue(proxy.selected(parent).isEmpty())
        assertFalse(proxy.current(operator))
    }

    @Test fun `only the exact audited flag predicate is accepted and no condition getter runs`() = withWorld {
        val poison = Poison()
        slot.N = poison
        assertTrue(proxy.selected(parent).isEmpty())
        assertEquals(0, poison.reads)
        slot.N = object : HasFlagBoolean() {
            override fun read(unit: OrderableUnit): Boolean = error("Subclass must not be evaluated")
        }.apply { id(1) }
        assertTrue(proxy.selected(parent).isEmpty())
        for (flag in listOf(HasFlagBoolean().apply { id(2) }, HasFlagBoolean().apply { id(1); flagMask = 1 shl 2 })) {
            slot.N = flag
            assertTrue(proxy.selected(parent).isEmpty())
        }
        slot.N = HasFlagBoolean().apply { id(1) }
        assertEquals(1, proxy.selected(parent).size)
    }

    @Test fun `slot visibility name position and both directions of attachment are required`() = withWorld {
        slot.o = false
        assertTrue(proxy.selected(parent).isEmpty())
        slot.o = true
        child.attachmentData = slot(1)
        assertTrue(proxy.selected(parent).isEmpty())
        child.attachmentData = slot
        child.parentEntity = actor("modularSpider")
        assertTrue(proxy.selected(parent).isEmpty())
        child.parentEntity = parent
        parent.children.clear()
        assertTrue(proxy.selected(parent).isEmpty())
        parent.children[slot] = child
        slot.javaClass.getDeclaredField("a").apply { isAccessible = true }.setShort(slot, 1)
        assertTrue(proxy.selected(parent).isEmpty())
        slot.javaClass.getDeclaredField("a").apply { isAccessible = true }.setShort(slot, 0)
        slot.javaClass.getDeclaredField("b").apply { isAccessible = true }.set(slot, "unitSlot7")
        assertTrue(proxy.selected(parent).isEmpty())
    }

    @Test fun `enemy dead cargo and unready parent attachments never become operators`() = withWorld {
        child.team = enemy
        assertTrue(proxy.selected(parent).isEmpty())
        child.team = own; child.isDead = true
        assertTrue(proxy.selected(parent).isEmpty())
        child.isDead = false; child.transportContainer = parent
        assertTrue(proxy.selected(parent).isEmpty())
        child.transportContainer = null; parent.transportContainer = child
        assertTrue(proxy.selected(parent).isEmpty())
        parent.transportContainer = null; parent.parentEntity = child
        assertTrue(proxy.selected(parent).isEmpty())
        parent.parentEntity = null; parent.buildProgress = 0.99f
        assertTrue(proxy.selected(parent).isEmpty())
        parent.buildProgress = Float.NaN
        assertTrue(proxy.selected(parent).isEmpty())
    }

    @Test fun `config upgrade slot and predicate replacement revoke a previously selected operator`() = withWorld {
        val original = proxy.selected(parent).single()
        child.unitConfig = configs.getValue("modularSpider_smallgunturret")
        val converted = proxy.selected(parent).single()
        assertFalse(original.sameOperator(converted))
        assertFalse(proxy.current(original))
        child.unitConfig.techLevel++
        val upgraded = proxy.selected(parent).single()
        assertFalse(converted.sameOperator(upgraded))
        slot.N = HasFlagBoolean().apply { id(1) }
        val predicateReplaced = proxy.selected(parent).single()
        assertFalse(upgraded.sameOperator(predicateReplaced))
        val newSlot = slot()
        bind(newSlot)
        assertFalse(predicateReplaced.sameOperator(proxy.selected(parent).single()))
    }

    @Test fun `ordinary stock parents and spider parent forms cannot masquerade as modules`() = withWorld {
        child.unitConfig = configs.getValue("modularSpider_nonEmpty")
        assertTrue(proxy.selected(parent).isEmpty())
        child.unitConfig = configs.getValue("mechFactory")
        assertTrue(proxy.selected(parent).isEmpty())
        child.unitConfig = configs.getValue("modularSpider_emptySlot")
        parent.unitConfig = configs.getValue("mechFactory")
        assertTrue(proxy.selected(parent).isEmpty())
    }

    @Test fun `destroyed objects and explicit order prohibition revoke the proxy independently of selection flags`() = withWorld {
        val original = proxy.selected(parent).single()
        parent.isDestroyed = true
        assertFalse(proxy.current(original))
        parent.isDestroyed = false; child.isDestroyed = true
        assertFalse(proxy.current(original))
        child.isDestroyed = false; child.unitConfig.canNotBeGivenOrdersByPlayer = true
        assertFalse(proxy.current(original))
        child.unitConfig.canNotBeGivenOrdersByPlayer = false; parent.unitConfig.canNotBeGivenOrdersByPlayer = true
        assertFalse(proxy.current(original))
    }
}
