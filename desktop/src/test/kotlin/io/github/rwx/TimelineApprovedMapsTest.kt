package io.github.rwx

import io.github.rwx.net.authoritative.PublicTerrainCatalog
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.GZIPInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.*

/** Reads approved static TMX metadata only. No engine, tileset runtime, simulation or GUI. */
class TimelineApprovedMapsTest {
    private val assets: Path get() = listOf(Path.of("assets"), Path.of("..", "assets"))
        .first { Files.isRegularFile(it.resolve(AuthoritativeDesktopMain.DEFAULT_MAP)) }.toAbsolutePath().normalize()
    private fun Element.children(tag: String): List<Element> = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == tag }
    private fun xml(path: Path): Element = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false; isExpandEntityReferences = false
    }.newDocumentBuilder().parse(path.toFile()).documentElement
    private data class MapData(val root: Element, val width: Int, val height: Int, val terrain: ByteArray,
                               val layers: Map<String, IntArray>, val unitProperties: Map<Int, Map<String, String>>)
    private fun read(map: String): MapData {
        require(map in TimelineDesktopServer.APPROVED_MAPS)
        val root = xml(assets.resolve(map)); val width = root.getAttribute("width").toInt(); val height = root.getAttribute("height").toInt()
        val layers = root.children("layer").associate { layer ->
            val data = layer.children("data").single()
            assertEquals("base64", data.getAttribute("encoding")); assertEquals("gzip", data.getAttribute("compression"))
            val packed = Base64.getMimeDecoder().decode(data.textContent)
            val raw = GZIPInputStream(ByteArrayInputStream(packed)).use { it.readBytes() }
            assertEquals(width * height * 4, raw.size)
            val bytes = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            layer.getAttribute("name") to IntArray(width * height) { bytes.int }
        }
        val sets = root.children("tileset").sortedBy { it.getAttribute("firstgid").toInt() }
        val definitions = HashMap<Int, Element>()
        fun definition(gid: Int): Pair<Int, Element> {
            val chosen = sets.last { it.getAttribute("firstgid").toInt() <= gid }
            val first = chosen.getAttribute("firstgid").toInt()
            val definition = definitions.getOrPut(first) {
                if (!chosen.hasAttribute("source")) chosen else {
                    val directory = assets.resolve("tilesets")
                    val path = directory.resolve(chosen.getAttribute("source")).normalize()
                    require(path.startsWith(directory)); xml(path)
                }
            }
            return (gid - first) to definition
        }
        fun properties(local: Int, definition: Element): Map<String, String> = definition.children("tile")
            .singleOrNull { it.getAttribute("id").toInt() == local }?.children("properties")?.singleOrNull()
            ?.children("property")?.associate { it.getAttribute("name") to it.getAttribute("value") } ?: emptyMap()
        val terrain = ByteArray(width * height)
        for (name in listOf("Ground", "Items")) for ((index, gid) in layers.getValue(name).withIndex()) {
            if (gid == 0) continue
            val (local, definition) = definition(gid)
            val image = definition.children("image").single().getAttribute("source").substringAfterLast('/')
            assertNotNull(PublicTerrainCatalog.id(image, local), "Used terrain must be in the closed public artwork catalog")
            val flags = properties(local, definition).keys
            val bits = (if ("water" in flags) 1 else 0) or
                (if (flags.any { it in setOf("cliff", "cliff-soft", "lava-cliff") }) 2 else 0) or
                (if ("res_pool" in flags) 4 else 0) or (if ("block-buildings" in flags) 8 else 0)
            terrain[index] = (terrain[index].toInt() or bits).toByte()
        }
        val units = layers.getValue("Units").withIndex().filter { it.value != 0 }.associate { (index, gid) ->
            val (local, definition) = definition(gid); index to properties(local, definition)
        }
        return MapData(root, width, height, terrain, layers, units)
    }

    @Test fun `approved Arena asset has 64 separated static starts and 64 public resources outside their footprints`() {
        val map = read(TimelineDesktopServer.ARENA_MAP)
        assertEquals(128, map.width); assertEquals(128, map.height)
        assertEquals(setOf("Ground", "Items", "Units"), map.layers.keys)
        assertEquals(0, map.root.getElementsByTagName("objectgroup").length)
        assertTrue(map.root.children("tileset").all { !it.hasAttribute("source") })
        assertTrue(map.layers.getValue("Ground").all { it == 1 })
        assertEquals(64, map.terrain.count { it.toInt() and 4 != 0 })
        assertTrue(map.terrain.all { it.toInt() in setOf(0, 4) })
        val initial = TimelineDesktopServer.initialStarts(TimelineDesktopServer.ARENA_MAP)
        val plan = TimelineStaticSpawns.plan(map.width, map.height, map.terrain, initial)
        assertEquals((2..63).toList(), plan.keys.toList())
        val starts = initial + plan.values.map { it.commandCenter }
        assertEquals(64, starts.size)
        for ((index, point) in starts.withIndex()) {
            for (other in starts.take(index)) assertTrue(abs(point.x - other.x) >= 9 || abs(point.y - other.y) >= 9)
            for (dy in -2..3) for (dx in -2..5) assertEquals(0, map.terrain[(point.y + dy) * map.width + point.x + dx].toInt())
            assertTrue(map.terrain.withIndex().any { (cell, value) -> value.toInt() and 4 != 0 &&
                abs(cell % map.width - point.x) <= 12 && abs(cell / map.width - point.y) <= 12 })
        }
        assertEquals(4, map.unitProperties.size)
        for ((team, point) in initial.withIndex()) {
            assertEquals(mapOf("unit" to "commandCenter", "team" to "$team"), map.unitProperties[point.y * map.width + point.x])
            assertEquals(mapOf("unit" to "builder", "team" to "$team"), map.unitProperties[point.y * map.width + point.x + 4])
        }
    }

    @Test fun `unchanged Small Island actual public flags retain four resources and only thirty optional land patches`() {
        val map = read(AuthoritativeDesktopMain.DEFAULT_MAP)
        assertEquals(110, map.width); assertEquals(110, map.height)
        assertEquals(4, map.terrain.count { it.toInt() and 4 != 0 })
        assertEquals(4375, map.terrain.count { it.toInt() == 0 })
        assertEquals(7337, map.terrain.count { it.toInt() and 1 != 0 })
        val plan = TimelineStaticSpawns.plan(map.width, map.height, map.terrain,
            TimelineDesktopServer.initialStarts(AuthoritativeDesktopMain.DEFAULT_MAP))
        assertEquals(30, plan.size)
        assertNull(plan[63], "A two-player island is not treated as a validated 64-seat map")
    }

    @Test fun `Large Arena has bounded public terrain 64 starts and space for 65536 real radius units`() {
        val map = read(TimelineDesktopServer.LARGE_ARENA_MAP)
        assertEquals(512, map.width); assertEquals(512, map.height)
        assertTrue(map.width * map.height <= PublicTerrainCatalog.MAX_VISUAL_CELLS)
        assertEquals(setOf("Ground", "Items", "Units"), map.layers.keys)
        assertEquals(0, map.root.getElementsByTagName("objectgroup").length)
        assertTrue(map.root.children("tileset").all { !it.hasAttribute("source") })
        assertEquals(setOf("Snow", "misc", "units"), map.root.children("tileset").map { it.getAttribute("name") }.toSet())
        assertTrue(map.layers.getValue("Ground").all { it == 1 })
        assertEquals(64, map.terrain.count { it.toInt() == 4 })
        assertTrue(map.terrain.all { it.toInt() in setOf(0, 4) })
        val initial = TimelineDesktopServer.initialStarts(TimelineDesktopServer.LARGE_ARENA_MAP)
        assertEquals(listOf(TimelineSpawnTile(8, 8), TimelineSpawnTile(503, 503)), initial)
        val reserved = TimelineStaticSpawns.plan(map.width, map.height, map.terrain, initial)
        assertEquals((2..63).toList(), reserved.keys.toList())
        val starts = initial + reserved.values.map { it.commandCenter }
        val resources = map.terrain.indices.filter { map.terrain[it].toInt() == 4 }
        for ((index, point) in starts.withIndex()) {
            for (other in starts.take(index)) assertTrue(abs(point.x - other.x) >= 9 || abs(point.y - other.y) >= 9)
            for (dy in -2..3) for (dx in -2..5) assertEquals(0, map.terrain[(point.y + dy) * map.width + point.x + dx].toInt())
            assertTrue(resources.any { cell -> abs(cell % map.width - point.x) <= 12 && abs(cell / map.width - point.y) <= 12 })
        }
        assertEquals(4, map.unitProperties.size)
        for ((team, point) in initial.withIndex()) {
            assertEquals(mapOf("unit" to "commandCenter", "team" to "$team"), map.unitProperties[point.y * map.width + point.x])
            assertEquals(mapOf("unit" to "builder", "team" to "$team"), map.unitProperties[point.y * map.width + point.x + 4])
        }
        // The real harness also reads the loaded native prototypes. This static test pins the
        // bundled replacement tank radius, rather than deriving density from a display sprite.
        val tankIni = Files.readString(assets.resolve("units/tanks/tank.ini"))
        val tankRadius = Regex("^\\s*radius\\s*:\\s*([0-9.]+)\\s*$", RegexOption.MULTILINE)
            .find(tankIni)?.groupValues?.get(1)?.toFloat() ?: error("Stock tank radius is missing")
        assertEquals(11f, tankRadius)
        val layout = TimelineScaleAcceptanceMain.plan(map.width, map.height, 20, 20, map.terrain,
            seats = 64, units = 65_536, tankRadius = tankRadius, baseRadius = 30f)
        assertEquals(64, layout.bases.size); assertEquals(65_472, layout.tanks.size)
        assertTrue(layout.availableTankSlots >= 65_472)
        assertEquals(26, layout.spacing)
        // Unique points on this 26-unit lattice are at least 26 apart: linear-space proof,
        // avoiding an O(N squared) all-pairs loop for 65,472 tank positions.
        val occupied = HashSet<Pair<Int, Int>>()
        for (point in layout.tanks) {
            assertEquals(point.x.toInt().toFloat(), point.x); assertEquals(point.y.toInt().toFloat(), point.y)
            assertEquals(layout.spacing / 2, point.x.toInt() % layout.spacing)
            assertEquals(layout.spacing / 2, point.y.toInt() % layout.spacing)
            assertTrue(occupied.add(point.x.toInt() to point.y.toInt()))
            for (base in layout.bases) assertTrue(hypot((point.x - base.x).toDouble(), (point.y - base.y).toDouble()) >= 61)
            for (y in ((point.y - 12) / 20).toInt()..((point.y + 12) / 20).toInt())
                for (x in ((point.x - 12) / 20).toInt()..((point.x + 12) / 20).toInt())
                    assertEquals(0, map.terrain[y * map.width + x].toInt())
        }
        for ((index, point) in layout.bases.withIndex()) for (other in layout.bases.take(index))
            assertTrue(hypot((point.x - other.x).toDouble(), (point.y - other.y).toDouble()) >= 60)
    }

    @Test fun `room defaults use Small Island for two Arena through sixteen and Large Arena above sixteen`() {
        val unset = AuthorityOptions("--authority-server", emptyMap())
        assertEquals(AuthoritativeDesktopMain.DEFAULT_MAP, TimelineDesktopServer.chooseMap(unset, 2))
        assertEquals(TimelineDesktopServer.ARENA_MAP, TimelineDesktopServer.chooseMap(unset, 3))
        assertEquals(TimelineDesktopServer.ARENA_MAP, TimelineDesktopServer.chooseMap(unset, 16))
        assertEquals(TimelineDesktopServer.LARGE_ARENA_MAP, TimelineDesktopServer.chooseMap(unset, 17))
        assertEquals(TimelineDesktopServer.LARGE_ARENA_MAP, TimelineDesktopServer.chooseMap(unset, 64))
        for (count in listOf(1, 65)) assertFailsWith<IllegalArgumentException> { TimelineDesktopServer.chooseMap(unset, count) }
    }

    @Test fun `explicit approved map is respected at every default room size boundary`() {
        assertEquals(setOf(AuthoritativeDesktopMain.DEFAULT_MAP, TimelineDesktopServer.ARENA_MAP,
            TimelineDesktopServer.LARGE_ARENA_MAP), TimelineDesktopServer.APPROVED_MAPS)
        val unset = AuthorityOptions("--authority-server", emptyMap())
        for (map in TimelineDesktopServer.APPROVED_MAPS) for (count in listOf(2, 3, 16, 17, 64)) {
            val explicit = unset.copy(values = mapOf("map" to map))
            assertEquals(map, TimelineDesktopServer.chooseMap(explicit, count))
        }
        assertFailsWith<IllegalArgumentException> { TimelineDesktopServer.chooseMap(unset.copy(values = mapOf("map" to "unapproved.tmx")), 3) }
    }
}
