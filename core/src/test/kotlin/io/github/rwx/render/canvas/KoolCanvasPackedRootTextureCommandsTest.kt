package io.github.rwx.render.canvas

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.scene.Mesh
import de.fabmax.kool.scene.Scene
import kotlin.test.*

class KoolCanvasPackedRootTextureCommandsTest {
    init { if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm()) }
    private val viewport = KoolCanvasViewport(640, 480)
    private val a = KoolCanvasTextureRef(KoolCanvasTextureId("packed-root-a"), 8, 8, hasAlpha = true)
    private val b = KoolCanvasTextureRef(KoolCanvasTextureId("packed-root-b"), 8, 8, hasAlpha = true)
    private fun store(enabled: Boolean) = KoolCanvasCpuTextureStore().apply {
        configurePackedRootTextureCommandsForTest(enabled)
        registerArgb(a.id, 8, 8, IntArray(64) { 0xff20a030.toInt() }, alphaBleed = true)
        registerArgb(b.id, 8, 8, IntArray(64) { 0x80c06020.toInt() }, alphaBleed = true)
    }
    private fun command(index: Int = 0, ref: KoolCanvasTextureRef = a,
                        state: KoolCanvasState = KoolCanvasState.Default, paint: KoolCanvasPaint = KoolCanvasPaint.Default) =
        KoolCanvasCommand.DrawTexture(ref, KoolCanvasRect(-0f, 1f, 7f, 8f),
            KoolCanvasRect(index.toFloat(), 12.5f, index + 9.75f, 31.5f), paint, state)
    private fun freeze(store: KoolCanvasCpuTextureStore, commands: List<KoolCanvasCommand>, sequence: Long = 1,
                       stats: KoolCanvasVisualStats? = null) =
        store.freezeFrame(KoolCanvasFrame(viewport, commands, stats), sequence, 1, 0, 0)
    private fun raw(draw: KoolCanvasCommand.DrawTexture): List<Int> {
        val s=draw.source; val d=draw.destination; val t=draw.state.transform
        return listOf(s.left,s.top,s.right,s.bottom,d.left,d.top,d.right,d.bottom,
            t.scaleX,t.skewY,t.skewX,t.scaleY,t.translateX,t.translateY).map { it.toRawBits() }
    }

    @Test fun immutableLegacyListViewKeepsLogicalOrderAllRawBitsAndFullMetadataWithoutRetainingDraws() {
        val store=store(true)
        val clip=KoolCanvasRect(-0f,3.5f,500.25f,430.5f)
        val first=command(state=KoolCanvasState(transform=KoolCanvasTransform(-0f, .2f, -.3f, 1.1f, 4.25f, -7.5f),
            clip=clip,drawRole=KoolCanvasDrawRole.UnitShadow,semanticUnitId=Long.MAX_VALUE-17),
            paint=KoolCanvasPaint(textureEffect=KoolCanvasTextureEffect.TeamColor(
                KoolCanvasTeamColorMode.HueShift,KoolCanvasColor(0xff1a2b3c.toInt()),.37f)))
        val barrier=KoolCanvasCommand.DrawRect(KoolCanvasRect(1f,2f,9f,10f),KoolCanvasPaint.Default,KoolCanvasState.Default)
        val input=mutableListOf<KoolCanvasCommand>(first,barrier,command(20,b))
        val frame=freeze(store,input)
        try {
            val packed=assertIs<KoolCanvasPackedTextureCommands>(frame.frame.commands)
            assertEquals(3,packed.size);assertEquals(2,packed.packedSize)
            assertEquals(0,packed.materializedCount())
            assertNull(packed.legacyAt(0));assertNull(packed.legacyAt(2))
            assertNotNull(packed.legacyAt(1))
            input.clear()
            val decoded=assertIs<KoolCanvasCommand.DrawTexture>(packed[0])
            assertEquals(raw(first),raw(decoded))
            assertEquals(first.state,decoded.state);assertEquals(first.paint,decoded.paint)
            assertEquals(Long.MAX_VALUE-17,decoded.state.semanticUnitId)
            assertSame(decoded,packed[0]);assertEquals(1,packed.materializedCount())
            val expected=listOf(first.copy(texture=packed.texture(packed.rowAt(0))),barrier,
                command(20,b).copy(texture=packed.texture(packed.rowAt(2))))
            assertEquals(expected,packed.toList());assertEquals(expected.hashCode(),packed.hashCode())
            @Suppress("UNCHECKED_CAST")
            assertFails { (packed as MutableList<KoolCanvasCommand>).clear() }
            assertFailsWith<IndexOutOfBoundsException> { packed[-1] }
            assertFailsWith<IndexOutOfBoundsException> { packed[3] }
        } finally {frame.close();store.close()}
    }

    @Test fun immutablePixelVersionsRemainPinnedWhileNestedDynamicAndMissingDrawsUseOriginalFreeze() {
        val store=store(true)
        val nestedId=KoolCanvasTextureId("packed-nested")
        store.registerFrame(nestedId,KoolCanvasFrame(viewport,listOf(command())))
        val dynamic=KoolCanvasTextureRef(KoolCanvasTextureId("packed-dynamic"),8,8)
        store.registerArgb(dynamic.id,8,8,IntArray(64){0xff998877.toInt()},alphaBleed=false)
        val missing=KoolCanvasTextureRef(KoolCanvasTextureId("packed-missing"),8,8)
        val premul=KoolCanvasTextureRef(KoolCanvasTextureId("packed-premul"),8,8,premultipliedAlpha=true)
        store.registerPremultipliedArgb(premul.id,8,8,IntArray(64){0xff665544.toInt()})
        val nanPaint=KoolCanvasPaint(textureEffect=KoolCanvasTextureEffect.TeamColor(
            KoolCanvasTeamColorMode.HueAdd,KoolCanvasColor.White,Float.fromBits(0x7fc01234)))
        val displacement=KoolCanvasPaint(textureEffect=KoolCanvasTextureEffect.Displacement(b,.25f))
        val input=listOf(command(),command(ref=KoolCanvasTextureRef(nestedId,640,480)),command(ref=dynamic),
            command(ref=missing),command(ref=premul),command(paint=nanPaint),command(paint=displacement))
        val first=freeze(store,input)
        store.registerArgb(a.id,8,8,IntArray(64){0xff001122.toInt()},alphaBleed=true)
        val second=freeze(store,listOf(command()),2)
        try {
            val old=assertIs<KoolCanvasPackedTextureCommands>(first.frame.commands)
            val fresh=assertIs<KoolCanvasPackedTextureCommands>(second.frame.commands)
            assertEquals(1,old.packedSize)
            for(index in 1 until input.size)assertNotNull(old.legacyAt(index))
            val oldRef=old.texture(0);val newRef=fresh.texture(0)
            assertNotEquals(oldRef.id,newRef.id);assertEquals(oldRef.frozenPixelIdentity,newRef.frozenPixelIdentity)
            val oldImage=assertIs<FrozenCanvasResource.Pixels>(first.resourceLease.resources()[oldRef.id]).image
            val newImage=assertIs<FrozenCanvasResource.Pixels>(second.resourceLease.resources()[newRef.id]).image
            assertEquals(0xff20a030.toInt(),oldImage.pixels[0]);assertEquals(0xff001122.toInt(),newImage.pixels[0])
            val nested=first.resourceLease.resources().values.filterIsInstance<FrozenCanvasResource.Frame>().single()
            assertFalse(nested.frame.commands is KoolCanvasPackedTextureCommands,"offscreen/nested freeze remains original")
            assertEquals(0,old.materializedCount());assertEquals(0,fresh.materializedCount())
        } finally {second.close();first.close();store.close()}
    }

    @Test fun thirtyThousandSpritesUseBoundedImmutableChunksInsteadOfOneHumongousFloatArray() {
        val store=store(true)
        val frame=freeze(store,List(30000){command(it)})
        try {
            val packed=assertIs<KoolCanvasPackedTextureCommands>(frame.frame.commands)
            assertEquals(30000,packed.packedSize)
            assertEquals((30000+511)/512,packed.chunkCount())
            assertEquals(512*14*4,packed.largestCoordinateChunkBytes())
            for(index in listOf(0,511,512,1023,1024,29999)) {
                val row=packed.rowAt(index)
                assertEquals(index.toFloat().toRawBits(),packed.coordinate(row,4).toRawBits())
                assertNull(packed.legacyAt(index))
            }
            assertEquals(0,packed.materializedCount())
        } finally {frame.close();store.close()}
    }

    @Test fun publishingFreezesExclusiveBuilderOwnershipAndRejectsEveryLaterMutation() {
        val builder=KoolCanvasPackedTextureCommands.Builder(1)
        val original=command(state=KoolCanvasState(transform=KoolCanvasTransform(
            scaleX=Float.fromBits(0x7fc01234),translateX=-0f),semanticUnitId=Long.MIN_VALUE+19))
        val textureIndex=builder.textureIndex(a)
        builder.add(original,textureIndex)
        val published=assertIs<KoolCanvasPackedTextureCommands>(builder.build())
        assertEquals(0,published.materializedCount())
        assertFailsWith<IllegalStateException>{builder.add(original,textureIndex)}
        assertFailsWith<IllegalStateException>{builder.addLegacy(original)}
        assertFailsWith<IllegalStateException>{builder.textureIndex(b)}
        assertFailsWith<IllegalStateException>{builder.build()}
        assertEquals(raw(original),raw(assertIs<KoolCanvasCommand.DrawTexture>(published[0])))
        assertEquals(Long.MIN_VALUE+19,(published[0] as KoolCanvasCommand.DrawTexture).state.semanticUnitId)
        @Suppress("UNCHECKED_CAST")
        assertFails{(published.textures as MutableList<KoolCanvasTextureRef>).clear()}
    }

    private fun visible(scene:Scene)=scene.children.filterIsInstance<Mesh<*>>().filter{it.isVisible}.sortedBy{it.drawGroupId}
    private fun picture(scene:Scene)=visible(scene).map { mesh ->
        listOf(mesh.drawGroupId,mesh.shader!!::class.simpleName,mesh.geometry.numIndices,mesh.geometry.numVertices,
            mesh.instances?.numInstances,mesh.instances?.let { instances ->
                val data=instances.instanceData;List(data.limit*data.strideBytes/4){data.buffer.getInt32(it*4)}
            })
    }
    private fun mixed(count:Int):List<KoolCanvasCommand> {
        val commands=mutableListOf<KoolCanvasCommand>()
        repeat(count){index ->
            val state=KoolCanvasState(KoolCanvasTransform.Identity.translate(7.25f,-3.5f)
                .rotate((index%13-6)*17f,12f,8f).scale(if(index%3==0)-.75f else 1.25f,.9f),
                if(index%2==0)KoolCanvasRect(4.25f,5.5f,611.75f,455.25f) else null,
                drawRole=if(index%7==0)KoolCanvasDrawRole.UnitShadow else KoolCanvasDrawRole.Generic,
                semanticUnitId=0x100000000L+index)
            val paint=KoolCanvasPaint(KoolCanvasColor(0xafd08030.toInt()),alphaMultiplier=.7f,
                textureFilter=if(index%9==0)KoolCanvasTextureFilter.Nearest else KoolCanvasTextureFilter.Linear,
                blendMode=if(index%11==0)KoolCanvasBlendMode.Add else KoolCanvasBlendMode.SourceOver,
                textureEffect=KoolCanvasTextureEffect.TeamColor(KoolCanvasTeamColorMode.entries[index%3],
                    KoolCanvasColor(0xff148bc5.toInt()),.35f))
            commands+=command(index%100,if(index%2==0)a else b,state,paint)
            if(index%73==0)commands+=KoolCanvasCommand.DrawRect(KoolCanvasRect(1f,2f,12f,14f),
                KoolCanvasPaint.Default.copy(alphaMultiplier=.4f),KoolCanvasState.Default)
        }
        repeat(257){commands+=command(it)} // Real growth beyond the atlas instance list's initial capacity.
        for(id in 20L downTo 1L)commands+=command(id.toInt(),state=KoolCanvasState(
            drawRole=KoolCanvasDrawRole.Waypoint,semanticUnitId=id))
        return commands
    }

    @Test fun realAtlasPrimitiveCursorMatchesAllInstanceBitsOrderAndAdaptiveMetadataWithoutDecoding() {
        for(adaptive in listOf(false,true)) {
            val control=store(false);val candidate=store(true)
            val left=Scene("packed-root-control");val right=Scene("packed-root-candidate")
            val lr=KoolCanvasFrameRenderer();val rr=KoolCanvasFrameRenderer()
            try {
                for((index,count) in listOf(320,9,0,600).withIndex()) {
                    val commands=mixed(count);val stats=KoolCanvasVisualStats(300,1000,adaptive)
                    val old=freeze(control,commands,index+1L,stats);val fresh=freeze(candidate,commands,index+1L,stats)
                    val oldInstall=FrozenCanvasGpuResources.install(old.resourceLease)
                    val newInstall=FrozenCanvasGpuResources.install(fresh.resourceLease)
                    try {
                        val packed=assertIs<KoolCanvasPackedTextureCommands>(fresh.frame.commands)
                        lr.render(left,old.frame);rr.render(right,fresh.frame)
                        assertEquals(picture(left),picture(right),"adaptive=$adaptive count=$count")
                        assertEquals(0,packed.materializedCount(),"normal atlas/adaptive replay must not call List.get for packed rows")
                        assertTrue(visible(right).any{it.shader is KoolCanvasSpriteShader && it.instances!!.numInstances>=257})
                    } finally {
                        newInstall.close();oldInstall.close();fresh.close();old.close()
                        KoolCanvasTextureRegistry.releaseRetiredTextures();KoolCanvasTextureRegistry.releaseRetiredTextures()
                    }
                }
            } finally {lr.releaseCachedMeshes(left);rr.releaseCachedMeshes(right);control.close();candidate.close()}
        }
    }

    @Test fun invalidSourceRegistrySlotMissCustomStoreAndDisabledAtlasKeepCompleteLegacyFallback() {
        for(mode in listOf("invalid-source","registry-slot-miss","custom-null-static","atlas-disabled")) {
            val control=store(false);val candidate=store(true)
            val wide=KoolCanvasTextureRef(KoolCanvasTextureId("packed-too-wide"),2046,1,hasAlpha=true)
            for(store in listOf(control,candidate))store.registerArgb(wide.id,2046,1,
                IntArray(2046){0xff41b273.toInt()},alphaBleed=true)
            val commands=when(mode) {
                "invalid-source" -> listOf(command(ref=wide)) // bottom=8 exceeds actual height=1: original plain fallback.
                "registry-slot-miss" -> listOf(command(ref=wide).copy(source=KoolCanvasRect.fromSize(2046f,1f)))
                else -> listOf(command(),command(ref=b))
            }
            val old=freeze(control,commands);val fresh=freeze(candidate,commands)
            val oldInstall=FrozenCanvasGpuResources.install(old.resourceLease)
            val newInstall=FrozenCanvasGpuResources.install(fresh.resourceLease)
            val renderStore=if(mode=="custom-null-static") object : KoolCanvasTextureStore by KoolCanvasTextureRegistry {
                override fun staticArgbImage(id:KoolCanvasTextureId):KoolCanvasArgbImage?=null
            } else KoolCanvasTextureRegistry
            val atlasEnabled=mode!="atlas-disabled"
            val left=Scene("packed-fallback-control");val right=Scene("packed-fallback-candidate")
            val lr=KoolCanvasFrameRenderer(renderStore,useSpriteAtlas=atlasEnabled)
            val rr=KoolCanvasFrameRenderer(renderStore,useSpriteAtlas=atlasEnabled)
            try {
                if(mode=="invalid-source")assertFalse(fresh.frame.commands is KoolCanvasPackedTextureCommands)
                else assertIs<KoolCanvasPackedTextureCommands>(fresh.frame.commands)
                lr.render(left,old.frame);rr.render(right,fresh.frame)
                assertEquals(picture(left),picture(right),mode)
                (fresh.frame.commands as? KoolCanvasPackedTextureCommands)?.let { packed ->
                    assertEquals(packed.packedSize,packed.materializedCount(),mode)
                }
            } finally {
                lr.releaseCachedMeshes(left);rr.releaseCachedMeshes(right);newInstall.close();oldInstall.close()
                fresh.close();old.close();control.close();candidate.close()
                KoolCanvasTextureRegistry.releaseRetiredTextures();KoolCanvasTextureRegistry.releaseRetiredTextures()
            }
        }
    }

    @Test fun lateRepeatAndNoOpKeepFirstUseOrderAfterAnExpiredFullAtlasIsPruned() {
        val control=store(false);val candidate=store(true)
        val oldTexture=KoolCanvasTextureRef(KoolCanvasTextureId("packed-expiring"),8,8,hasAlpha=true)
        for(store in listOf(control,candidate))store.registerArgb(oldTexture.id,8,8,
            IntArray(64){0xff3355aa.toInt()},alphaBleed=true)
        val oldControl=freeze(control,listOf(command(ref=oldTexture)))
        val oldCandidate=freeze(candidate,listOf(command(ref=oldTexture)))
        val repeatB=KoolCanvasCommand.DrawTextureRepeat(b,KoolCanvasRect.fromSize(8f,8f),
            KoolCanvasRect(10f,10f,26f,18f),2,KoolCanvasPaint.Default,KoolCanvasState.Default)
        // Neither the early NoOp nor eager palette resolution may reserve A after prepare prunes.
        val commands=listOf(command(paint=KoolCanvasPaint.Default.copy(alphaMultiplier=0f)),repeatB,command(40))
        val nextControl=freeze(control,commands,2);val nextCandidate=freeze(candidate,commands,2)
        val packets=listOf(oldControl,oldCandidate,nextControl,nextCandidate)
        val installs=packets.map { FrozenCanvasGpuResources.install(it.resourceLease) }
        val left=Scene("packed-expiry-control");val right=Scene("packed-expiry-candidate")
        val lr=KoolCanvasFrameRenderer().apply { configureSpriteAtlasForTest(16,1) }
        val rr=KoolCanvasFrameRenderer().apply { configureSpriteAtlasForTest(16,1) }
        try {
            lr.render(left,oldControl.frame);rr.render(right,oldCandidate.frame)
            val empty=KoolCanvasFrame(viewport,emptyList())
            // Last used at prepare1; prepare61 sees age60 and retains the full page.
            repeat(60){lr.render(left,empty);rr.render(right,empty)}
            lr.render(left,nextControl.frame);rr.render(right,nextCandidate.frame)
            assertEquals(picture(left),picture(right))
            val meshes=visible(right)
            assertIs<KoolCanvasSpriteShader>(meshes.first().shader)
            assertEquals(2,meshes.first().instances!!.numInstances,"legacy Repeat B must claim the newly available page first")
            val packed=assertIs<KoolCanvasPackedTextureCommands>(nextCandidate.frame.commands)
            assertEquals(2,packed.packedSize)
            assertEquals(2,packed.materializedCount(),"NoOp A and post-Repeat slot-miss A both use full legacy fallback")
        } finally {
            lr.releaseCachedMeshes(left);rr.releaseCachedMeshes(right)
            installs.asReversed().forEach{it.close()};packets.forEach{it.close()};control.close();candidate.close()
            KoolCanvasTextureRegistry.releaseRetiredTextures();KoolCanvasTextureRegistry.releaseRetiredTextures()
        }
    }

    @Test fun customLazyStaticAvailabilityKeepsOriginalLookupAndCapacityOrder() {
        val control=store(false);val candidate=store(true)
        val old=freeze(control,listOf(command(),command(20),command(40,b)))
        val fresh=freeze(candidate,listOf(command(),command(20),command(40,b)))
        val oldInstall=FrozenCanvasGpuResources.install(old.resourceLease)
        val newInstall=FrozenCanvasGpuResources.install(fresh.resourceLease)
        class LazyStore(private val firstId:KoolCanvasTextureId):KoolCanvasTextureStore by KoolCanvasTextureRegistry {
            val calls=mutableListOf<String>()
            private var firstCalls=0
            override fun staticArgbImage(id:KoolCanvasTextureId):KoolCanvasArgbImage? {
                val first=id==firstId
                calls+=if(first)"A" else "B"
                if(first && ++firstCalls==1)return null
                return KoolCanvasTextureRegistry.staticArgbImage(id)
            }
        }
        val oldStore=LazyStore((old.frame.commands[0] as KoolCanvasCommand.DrawTexture).texture.id)
        val packed=assertIs<KoolCanvasPackedTextureCommands>(fresh.frame.commands)
        val newStore=LazyStore(packed.texture(packed.rowAt(0)).id)
        val lr=KoolCanvasFrameRenderer(oldStore).apply{configureSpriteAtlasForTest(16,1)}
        val rr=KoolCanvasFrameRenderer(newStore).apply{configureSpriteAtlasForTest(16,1)}
        val left=Scene("packed-lazy-control");val right=Scene("packed-lazy-candidate")
        try {
            lr.render(left,old.frame);rr.render(right,fresh.frame)
            assertEquals(oldStore.calls,newStore.calls)
            assertEquals(listOf("A","A","B","B"),oldStore.calls)
            assertEquals(picture(left),picture(right))
            assertEquals(3,packed.materializedCount(),"custom stores intentionally consume the legacy List view")
        } finally {
            lr.releaseCachedMeshes(left);rr.releaseCachedMeshes(right);newInstall.close();oldInstall.close()
            fresh.close();old.close();control.close();candidate.close()
            KoolCanvasTextureRegistry.releaseRetiredTextures();KoolCanvasTextureRegistry.releaseRetiredTextures()
        }
    }

    @Test fun realHostSurfaceClearPrefixPreservesPrimitiveBackingAndInstanceBits() {
        val control=store(false);val candidate=store(true)
        val input=mixed(160)
        val old=freeze(control,input);val fresh=freeze(candidate,input)
        val oldInstall=FrozenCanvasGpuResources.install(old.resourceLease)
        val newInstall=FrozenCanvasGpuResources.install(fresh.resourceLease)
        val left=Scene("packed-surface-control");val right=Scene("packed-surface-candidate")
        val lr=KoolCanvasFrameRenderer();val rr=KoolCanvasFrameRenderer()
        try {
            val base=assertIs<KoolCanvasPackedTextureCommands>(fresh.frame.commands)
            val cleared=fresh.frame.withSurfaceClear()
            val view=assertIs<KoolCanvasPackedTextureCommands>(cleared.commands)
            assertEquals(base.size+1,view.size)
            assertEquals(KoolCanvasCommand.Clear(KoolCanvasColor(0xff000000.toInt()),KoolCanvasBlendMode.Source),view[0])
            assertEquals(0,base.materializedCount());assertSame(cleared,cleared.withSurfaceClear())
            lr.render(left,old.frame.withSurfaceClear());rr.render(right,cleared)
            assertEquals(picture(left),picture(right))
            assertEquals(0,base.materializedCount());assertEquals(0,view.materializedCount())
            assertSame(base[0],view[1],"public prefix get delegates to the same stable identity cache")
        } finally {
            lr.releaseCachedMeshes(left);rr.releaseCachedMeshes(right);newInstall.close();oldInstall.close()
            fresh.close();old.close();control.close();candidate.close()
            KoolCanvasTextureRegistry.releaseRetiredTextures();KoolCanvasTextureRegistry.releaseRetiredTextures()
        }
    }

    @Test fun existingRootClearOfEveryBlendKeepsFrameIdentityWithoutDecodingPackedRows() {
        for(blend in KoolCanvasBlendMode.entries) {
            val store=store(true)
            val frame=freeze(store,listOf(command(),KoolCanvasCommand.Clear(KoolCanvasColor.White,blend),command(20,b)))
            try {
                val packed=assertIs<KoolCanvasPackedTextureCommands>(frame.frame.commands)
                assertTrue(packed.hasRootClear());assertSame(frame.frame,frame.frame.withSurfaceClear())
                assertEquals(0,packed.materializedCount())
            } finally {frame.close();store.close()}
        }
    }

    @Test fun concurrentPublicGetPublishesOneStableDecodedIdentityAndExactRawBits() {
        val builder=KoolCanvasPackedTextureCommands.Builder(1)
        val original=command(state=KoolCanvasState(KoolCanvasTransform(scaleX=Float.fromBits(0x7fc01234)),
            semanticUnitId=Long.MAX_VALUE))
        builder.add(original,builder.textureIndex(a))
        val list=assertIs<KoolCanvasPackedTextureCommands>(builder.build())
        val ready=java.util.concurrent.CountDownLatch(8);val startGate=java.util.concurrent.CountDownLatch(1)
        val results=java.util.concurrent.atomic.AtomicReferenceArray<KoolCanvasCommand>(8)
        val errors=java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val workers=List(8){index -> Thread {
            ready.countDown();startGate.await()
            try{results.set(index,list[0])}catch(t:Throwable){errors.add(t)}
        }.apply{isDaemon=true;start()} }
        try {
            assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));startGate.countDown()
            workers.forEach{it.join(5000);assertFalse(it.isAlive)}
            assertTrue(errors.isEmpty())
            val first=assertIs<KoolCanvasCommand.DrawTexture>(results.get(0))
            for(index in 1 until 8)assertSame(first,results.get(index))
            assertEquals(raw(original),raw(first));assertEquals(Long.MAX_VALUE,first.state.semanticUnitId)
            assertEquals(1,list.materializedCount())
        } finally {startGate.countDown()}
    }

    @Test fun canonicalPaintSingletonsRemainDistinctFromValueEqualCustomPaints() {
        val builder=KoolCanvasPackedTextureCommands.Builder(4)
        val index=builder.textureIndex(a)
        val custom=KoolCanvasPaint()
        val customNearest=KoolCanvasPaint(textureFilter=KoolCanvasTextureFilter.Nearest)
        for(paint in listOf(custom,KoolCanvasPaint.Default,KoolCanvasPaint.DefaultNearest,customNearest))
            builder.add(command(paint=paint),index)
        val packed=assertIs<KoolCanvasPackedTextureCommands>(builder.build())
        assertSame(custom,(packed[0] as KoolCanvasCommand.DrawTexture).paint)
        assertSame(KoolCanvasPaint.Default,(packed[1] as KoolCanvasCommand.DrawTexture).paint)
        assertSame(KoolCanvasPaint.DefaultNearest,(packed[2] as KoolCanvasCommand.DrawTexture).paint)
        assertSame(customNearest,(packed[3] as KoolCanvasCommand.DrawTexture).paint)
    }
}
