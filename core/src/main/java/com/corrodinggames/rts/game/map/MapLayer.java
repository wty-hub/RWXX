package com.corrodinggames.rts.game.map;

import com.corrodinggames.rts.game.units.custom.logicBooleans.VariableScope;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.graphics.GamePaint;
import com.corrodinggames.rts.gameFramework.graphics.GraphicsEngine;
import com.corrodinggames.rts.gameFramework.graphics.Texture;
import com.corrodinggames.rts.gameFramework.utility.Log;
import com.corrodinggames.rts.gameFramework.utility.SlickToAndroidKeycodes;
import io.github.rwx.geometry.Point;
import io.github.rwx.geometry.Rect;
import io.github.rwx.geometry.RectF;
import io.github.rwx.render.canvas.KoolArgbColor;
import io.github.rwx.render.canvas.KoolMultiplyAddColorFilter;
import io.github.rwx.render.canvas.KoolPaint;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/* JADX INFO: renamed from: com.corrodinggames.rts.game.b.e */
/* JADX INFO: loaded from: game-lib.jar:com/corrodinggames/rts/game/b/e.class */
public class MapLayer {

    /* JADX INFO: renamed from: x */
    private static byte[] base64DecodeTable = new byte[SlickToAndroidKeycodes.AndroidCodes.KEYCODE_TV_CONTENTS_MENU];

    /* JADX INFO: renamed from: a */
    static GamePaint paintFillBlack;

    /* JADX INFO: renamed from: b */
    static GamePaint[] alphaFillPaints;

    /* JADX INFO: renamed from: c */
    static GamePaint dynamicAlphaFillPaint;

    /* JADX INFO: renamed from: d */
    static GamePaint groundTexturePaint;
    /* JADX INFO: renamed from: e */
    static GamePaint groundScaledTexturePaint;

    /* JADX INFO: renamed from: f */
    static GamePaint objectsTexturePaint;

    /* JADX INFO: renamed from: g */
    static GamePaint objectsScaledTexturePaint;

    /* JADX INFO: renamed from: h */
    static GamePaint[] lightingPaints;

    /**
     * Fold a row run of tiles into one tiled draw.
     *
     * **Off: it covers the map with black bands.** Measured with `desktop/tools/tilerun_repro.py`, which
     * repeats a configuration and reports the share of near-black pixels over the map area only:
     *
     *   run off - 0.9%, 0.9%, 1.0%   (3 runs)
     *   run on  - 37.3%, 32.5%, 49.8%, 38.1%, 29.0%   (5 runs)
     *
     * The two distributions do not overlap, so this is deterministic and reproducible, not a race. The
     * original build on the same replay shows no such bands.
     *
     * An earlier note here called it non-deterministic on the strength of one clean single-shot capture.
     * Eight samples against a quantitative measure refute that: a lone clean capture was a sampling
     * artifact, so the defect must be found by reasoning about the geometry rather than by re-running.
     *
     * The tiling primitive itself is pixel-exact (`DrawTextureRepeat`, oracle 1537 checks, 0/768), and the
     * widened destination and the repeat count now change together in one branch, so the stretched
     * single-quad shape can no longer come from those two drifting apart.
     *
     * **Cause: not yet established**, but the search space is now tightly bounded. Four candidates have
     * been tested and excluded, each against the same criterion (share of near-black pixels over the map
     * area; `desktop/tools/tilerun_repro.py`; baseline with this merge off is 0.9%):
     *
     *  - missing coverage: drawing only the first tile of each run makes it worse (58% against 37%), so the
     *    runs are covered;
     *  - vanilla's smooth-fog merge below also advancing `i7`: gating this merge off the fog pass moved it
     *    from 37% to 30%;
     *  - the tiling draw skipping `toAtlasDraw`: adding the remap moved it from 37% to 33%;
     *  - inconsistent vertical extent within a run: `i7` drives only `f15`/`f17` and `i6` is constant across
     *    the inner loop, so every tile of a run necessarily shares one y span.
     *
     * What remains is the horizontal geometry accumulated while expanding `repeat` into quads: the command's
     * total width is `N * f12 + f13` while the expansion divides it into `N` equal parts of `f12 + f13/N`.
     * That is sub-pixel and cannot explain 30% black on its own, so the next step is to measure the expanded
     * width against the destination width rather than to guess another hypothesis.
     *
     * `RWX_MAP_TILE_RUN_TILE=1` re-enables this merge to continue on that.
     */
    private static final boolean MAP_CELL_TILE_RUN_TILE =
        "1".equals(System.getenv("RWX_MAP_TILE_RUN_TILE"));

    /**
     * Longest row run that may be folded into one tiled draw.
     *
     * Runs average 9.40 tiles and reach 74, and folding them removes about 38.6% of the terrain commands, so
     * the merge is worth finishing. This bound only guards against a pathological full-row run producing one
     * enormous quad.
     */
    private static final int MAX_TILE_RUN = 256;

    /** Diagnostic: tiles visited and how many were folded into a merged run. */
    private static final boolean traceTileRuns = "1".equals(System.getenv("RWX_MAP_TILE_RUN_TRACE"));
    public static long tileRunTiles;
    public static long tileRunMerged;
    public static long tileRunSingles;
    public static long tileRunNonAtlas;
    /** Diagnostic: total tiles covered by merged runs, and the longest run seen. */
    public static long tileRunMergedLengthSum;
    public static long tileRunMaxLength;

    static {
        if (traceTileRuns) {
            String path = System.getenv("RWX_MAP_CACHE_TRACE");
            if (path != null && !path.isEmpty()) {
                try {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(path + ".tileruns"),
                        "traceTileRuns=" + traceTileRuns + " tileRun=on maxRun=" + MAX_TILE_RUN + "\n");
                } catch (Throwable ignored) {
                    // Diagnostics only.
                }
            }
        }
    }

    /**
     * Emits the run counters into the canvas stage trace, which the harness already writes to a file.
     *
     * A shutdown hook cannot report them: the benchmark force-kills the game process, so hooks never run
     * (measured - the counters printed nothing). Called from the render path, where the trace is live.
     */
    public static void reportTileRunsIfTracing(long frameSequence) {
        // Counted before any gate, and written straight to a file. Every trace-based attempt at these
        // counters produced nothing while the file marker proved the switch had arrived, so the report no
        // longer depends on the canvas trace being enabled or on a call-site gate being reached.
        reportCalls++;
        if (traceTileRuns && reportCalls % 5000L == 0L) {
            String path = System.getenv("RWX_MAP_CACHE_TRACE");
            if (path != null && !path.isEmpty()) {
                try {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(path + ".tilehist"),
                        "reportCalls=" + reportCalls
                            + " visited=" + tileRunTiles
                            + " mergedRuns=" + tileRunMerged
                            + " singles=" + tileRunSingles
                            + " mergedTiles=" + tileRunMergedLengthSum
                            + " maxRun=" + tileRunMaxLength
                            + " nonAtlas=" + tileRunNonAtlas + "\n");
                } catch (Throwable ignored) {
                    // Diagnostics only.
                }
            }
        }
        if (!traceTileRuns) {
            return;
        }
        if (!reportedFirstCall || (frameSequence % 200) != 0) {
            return;
        }
        reportedFirstCall = true;
        io.github.rwx.render.canvas.CanvasStageTraceBridge.recordTileRuns(
            tileRunTiles, tileRunMerged + tileRunSingles, tileRunMerged);
        io.github.rwx.render.canvas.CanvasStageTraceBridge.recordTileRunPaths(tileRunNonAtlas, reportCalls);
    }

    public static long reportCalls;
    private static boolean reportedFirstCall;

    /* JADX INFO: renamed from: i */
    public TileMap tileMap;

    /* JADX INFO: renamed from: j */
    public int layerIndex;

    /* JADX INFO: renamed from: k */
    public String name;

    /* JADX INFO: renamed from: l */
    public String lowerName;

    /* JADX INFO: renamed from: m */
    public boolean isItemsLayer;

    /* JADX INFO: renamed from: n */
    public int widthTiles;

    /* JADX INFO: renamed from: o */
    public int heightTiles;

    /* JADX INFO: renamed from: p */
    public Properties properties;

    /* JADX INFO: renamed from: q */
    public short[] tileIds;

    /* JADX INFO: renamed from: r */
    public boolean isGroundLayer;

    /* JADX INFO: renamed from: s */
    public boolean isTerrainLayer;

    /* JADX INFO: renamed from: t */
    final Rect tempRect = new Rect();

    /* JADX INFO: renamed from: u */
    final Rect tempRect2 = new Rect();

    /* JADX INFO: renamed from: v */
    final RectF tempRectF = new RectF();

    /* JADX INFO: renamed from: w */
    public boolean hasAlpha;

    static {
        for (int i = 0; i < 256; i++) {
            base64DecodeTable[i] = -1;
        }
        for (int i2 = 65; i2 <= 90; i2++) {
            base64DecodeTable[i2] = (byte) (i2 - 65);
        }
        for (int i3 = 97; i3 <= 122; i3++) {
            base64DecodeTable[i3] = (byte) ((26 + i3) - 97);
        }
        for (int i4 = 48; i4 <= 57; i4++) {
            base64DecodeTable[i4] = (byte) ((52 + i4) - 48);
        }
        base64DecodeTable[43] = 62;
        base64DecodeTable[47] = 63;
        paintFillBlack = new GamePaint();
        paintFillBlack.b(-16777216);
        paintFillBlack.a(KoolPaint.Style.FILL);
        alphaFillPaints = new GamePaint[11];
        for (int i5 = 0; i5 <= 10; i5++) {
            alphaFillPaints[i5] = new GamePaint();
            alphaFillPaints[i5].b(-16777216);
            alphaFillPaints[i5].a(KoolPaint.Style.FILL);
            alphaFillPaints[i5].c(i5 * 25);
        }
        dynamicAlphaFillPaint = new GamePaint();
        dynamicAlphaFillPaint.b(-16777216);
        dynamicAlphaFillPaint.a(KoolPaint.Style.FILL);
        groundTexturePaint = new GamePaint();
        groundTexturePaint.a(false);
        groundTexturePaint.d(false);
        groundTexturePaint.b(false);
        groundScaledTexturePaint = new GamePaint();
        groundScaledTexturePaint.a(true);
        objectsTexturePaint = new GamePaint();
        objectsTexturePaint.a(false);
        objectsTexturePaint.d(false);
        objectsTexturePaint.b(false);
        objectsScaledTexturePaint = new GamePaint();
        objectsScaledTexturePaint.a(true);
        lightingPaints = new GamePaint[11];
        for (int i6 = 0; i6 <= 10; i6++) {
            GamePaint gamePaint = new GamePaint();
            gamePaint.a(new KoolMultiplyAddColorFilter(KoolArgbColor.a(255 - (i6 * 25), 255 - (i6 * 25), 255 - (i6 * 25)), 0));
            lightingPaints[i6] = gamePaint;
        }
    }

    /* JADX INFO: renamed from: a */
    public final MapTile getTileAt(int i, int i2) {
        if (this.tileIds == null) {
            this.tileIds = new short[this.widthTiles * this.heightTiles];
        }
        return this.tileMap.getUniqueTile(this.tileIds[(i * this.heightTiles) + i2]);
    }

    /* JADX INFO: renamed from: a */
    public short[] getTileIds() {
        if (this.tileIds == null) {
            this.tileIds = new short[this.widthTiles * this.heightTiles];
        }
        return this.tileIds;
    }

    /* JADX INFO: renamed from: a */
    public void setTileAt(int i, int i2, MapTile mapTile, boolean z) {
        if (this.tileIds == null) {
            this.tileIds = new short[this.widthTiles * this.heightTiles];
        }
        if (mapTile == null) {
            this.tileIds[(i * this.heightTiles) + i2] = 0;
            return;
        }
        if (z) {
            mapTile = this.tileMap.getTileVariant(mapTile, i, i2);
        }
        if (mapTile.isResourcePool) {
            boolean z2 = false;
            for (Point point : this.tileMap.unitObjects) {
                if (point.worldX == i && point.worldY == i2) {
                    GameEngine.log("resPools point:" + i + ", " + i2 + " already exists");
                    z2 = true;
                }
            }
            if (!z2) {
                this.tileMap.unitObjects.add(new Point(i, i2));
            }
        }
        if (mapTile.uniqueTileId == -1) {
            mapTile.uniqueTileId = this.tileMap.registerUniqueTile(mapTile);
        }
        this.tileIds[(i * this.heightTiles) + i2] = mapTile.uniqueTileId;
    }

    /* JADX INFO: renamed from: a */
    public void renderLayerRegion(GraphicsEngine graphicsEngine, float f, float f2, float f3, float f4, float f5, float f6, float f7, float f8, boolean z, boolean z2, boolean z3) {
        if (z3) graphicsEngine.beginDrawRole(GraphicsEngine.DRAW_ROLE_MAP_FOG, -1L);
        try {
            renderLayerRegionContents(graphicsEngine, f, f2, f3, f4, f5, f6, f7, f8, z, z2, z3);
        } finally {
            if (z3) graphicsEngine.endDrawRole();
        }
    }

    private void renderLayerRegionContents(GraphicsEngine graphicsEngine, float f, float f2, float f3, float f4, float f5, float f6, float f7, float f8, boolean z, boolean z2, boolean z3) {
        GamePaint gamePaint;
        TileAtlasCache tileAtlasCache;
        GamePaint gamePaint2;
        GameEngine gameEngine = GameEngine.getInstance();
        TileMap tileMap = this.tileMap;
        int i = (int) (f3 * tileMap.tileScaleX);
        if (i < 0) {
            i = 0;
        }
        int i2 = (int) (f4 * tileMap.tileScaleY);
        if (i2 < 0) {
            i2 = 0;
        }
        int i3 = (int) ((f3 + f5) * tileMap.tileScaleX);
        if (i3 > this.widthTiles - 1) {
            i3 = this.widthTiles - 1;
        }
        int i4 = (int) ((f4 + f6) * tileMap.tileScaleY);
        if (i4 > this.heightTiles - 1) {
            i4 = this.heightTiles - 1;
        }
        byte[][] bArr = tileMap.getFogDisplayData();
        z = z && tileMap.isFogDisplayEnabled();
        float f9 = f * f7;
        float f10 = f2 * f8;
        float f11 = tileMap.tileWorldSizeX * f7;
        float f12 = tileMap.tileWorldSizeY * f8;
        byte b = 15;
        if (!z2) {
            b = 10;
        }
        boolean z4 = tileMap.fogRenderActive;
        if (z4) {
            b = 15;
        }
        if (z && bArr == null) {
            z = false;
        }
        GamePaint gamePaint3 = alphaFillPaints[5];
        GamePaint gamePaint4 = paintFillBlack;
        GamePaint gamePaint5 = dynamicAlphaFillPaint;
        gamePaint5.c(255);
        if (z4) {
            gamePaint4 = alphaFillPaints[7];
            gamePaint5.c((int) ((1.0f - ((1.0f - (gamePaint3.f() / 255.0f)) * (1.0f - (gamePaint4.f() / 255.0f)))) * 255.0f));
        }
        boolean z5 = false;
        if (GameEngine.isPC() && f7 < 1.0f && f8 < 1.0f) {
            z5 = true;
        }
        if (z3) {
        }
        if (!this.isGroundLayer) {
            gamePaint = objectsTexturePaint;
            if (z5) {
                gamePaint = objectsScaledTexturePaint;
            }
        } else {
            gamePaint = groundTexturePaint;
            if (z5) {
                gamePaint = groundScaledTexturePaint;
            }
        }
        GamePaint gamePaint6 = gamePaint;
        float f13 = 0.0f;
        boolean z6 = false;
        if (!GameEngine.isPC()) {
            z6 = true;
        } else if (z3) {
            if (f7 < 1.0f || f8 >= 1.0f) {
            }
        } else if (f7 < 1.0f || f8 < 1.0f) {
            f13 = 0.5f * f7;
        }
        if (f7 < 0.5f) {
            tileAtlasCache = TileMap.fogTileAtlasCacheHalfScale;
        } else {
            tileAtlasCache = TileMap.fogTileAtlasCacheFullScale;
        }
        short[] tileIds = getTileIds();
        MapTile[] mapTileArr = tileMap.uniqueTiles;
        RectF rectF = this.tempRectF;
        Rect rect = this.tempRect2;
        int i5 = this.heightTiles;
        boolean z7 = this.isGroundLayer;
        Rect rect2 = this.tempRect;
        tileMap.ensureFogCacheAllocated();
        byte[][] bArr2 = tileMap.fogOfWarCurrent;
        byte[][] bArr3 = tileMap.fogOfWarNext;
        Texture texture = TileMap.fogSmoothAtlasTexture;
        for (int i6 = i; i6 < i3 + 1; i6++) {
            int i7 = i2;
            while (i7 < i4 + 1) {
                MapTile mapTile = mapTileArr[tileIds[(i6 * i5) + i7]];
                if (mapTile != null) {
                    byte b2 = 0;
                    if (z) {
                        b2 = bArr[i6][i7];
                    }
                    if (b2 != b) {
                        if (traceTileRuns) {
                            tileRunTiles++;
                        }
                        float f14 = (i6 * f11) + 0.0f;
                        float f15 = (i7 * f12) + 0.0f;
                        float f16 = ((i6 + 1) * f11) + f13;
                        float f17 = ((i7 + 1) * f12) + f13;
                        rectF.a(f14 - f9, f15 - f10, f16 - f9, f17 - f10);
                        if (z5 && !z3) {
                            rectF.b = (int) rectF.b;
                            rectF.a = (int) rectF.a;
                        }
                        if (!z3) {
                            Tileset tileset = mapTile.tileset;
                            if (!z6) {
                                if (mapTile.atlasSlotIndex >= 0) {
                                    // Merge a run of the row that shares this tile's atlas slot and fog state
                                    // into one tiled draw, the way vanilla's own smooth-fog block scans a row
                                    // (`rectF.d += (i9 - i7) * f12; i7 = i9;`). Without it every visible tile
                                    // is its own command: measured 5,000-6,000 draws per 512x512 layer cell.
                                    //
                                    // The destination is widened to the whole run, but the source rect is NOT:
                                    // the engine tiles that one source rect across the widened destination, so
                                    // the pixels equal N single-tile draws.
                                    //
                                    // `!z3` keeps this off the fog pass: vanilla's smooth-fog merge below also
                                    // advances `i7` in the same iteration, and only one of them may own it.
                                    // Only the main screen's fog pass sets `z3`; the offscreen cell recording,
                                    // where the terrain command count actually hurts, keeps the merge.
                                    int runEnd = i7;
                                    if (MAP_CELL_TILE_RUN_TILE && !z3 && z && b2 >= 5) {
                                        int limit = Math.min(i4 + 1, i7 + MAX_TILE_RUN);
                                        int candidate = i7 + 1;
                                        while (candidate < limit) {
                                            MapTile next = mapTileArr[tileIds[(i6 * i5) + candidate]];
                                            if (next == null || next.atlasSlotIndex != mapTile.atlasSlotIndex
                                                || bArr[i6][candidate] != b2) {
                                                break;
                                            }
                                            candidate++;
                                        }
                                        runEnd = candidate - 1;
                                    }
                                    int repeatCount = runEnd - i7 + 1;
                                    if (repeatCount > 1) {
                                        rectF.c = ((runEnd + 1) * f12) + f13 - f10;
                                        if (z5 && !z3) {
                                            rectF.b = (int) rectF.b;
                                            rectF.a = (int) rectF.a;
                                        }
                                    }
                                    graphicsEngine.a(
                                        tileAtlasCache.getAtlasTextureForIndex(mapTile.atlasSlotIndex),
                                        tileAtlasCache.getRectForIndex(mapTile.atlasSlotIndex),
                                        rectF,
                                        gamePaint6,
                                        repeatCount);
                                    if (traceTileRuns) {
                                        if (runEnd > i7) {
                                            tileRunMerged++;
                                            long runLength = runEnd - i7 + 1L;
                                            tileRunMergedLengthSum += runLength;
                                            if (runLength > tileRunMaxLength) tileRunMaxLength = runLength;
                                        } else {
                                            tileRunSingles++;
                                        }
                                    }
                                    i7 = runEnd;
                                } else {
                                    if (traceTileRuns) {
                                        tileRunNonAtlas++;
                                    }
                                    mapTile.renderTile(graphicsEngine, rectF, f7, gamePaint6);
                                }
                            } else {
                                rect.a((int) (f14 - f9), (int) (f15 - f10), (int) (f16 - f9), (int) (f17 - f10));
                                if (mapTile.atlasSlotIndex >= 0) {
                                    graphicsEngine.b(tileAtlasCache.getAtlasTextureForIndex(mapTile.atlasSlotIndex), tileAtlasCache.getRectForIndex(mapTile.atlasSlotIndex), rect, gamePaint6);
                                } else {
                                    graphicsEngine.a(tileset.tilesetBitmap, tileset.getTileRectCached(mapTile.tilesetLocalIndex), rect, gamePaint6);
                                }
                            }
                        }
                        if (z && z7 && z2 && (b2 != 0 || bArr3[i6][i7] != 0 || bArr2[i6][i7] != 0)) {
                            if (b2 >= 5) {
                                if (z3 && (b2 == 10 || bArr2[i6][i7] == 0)) {
                                    int i8 = i7 + 1;
                                    while (i8 < i4) {
                                        if (b2 != bArr[i6][i8] || (b2 != 10 && bArr2[i6][i8] != 0)) {
                                            break;
                                        } else {
                                            i8++;
                                        }
                                    }
                                    int i9 = i8 - 1;
                                    if (i9 > i7) {
                                        rectF.d += (i9 - i7) * f12;
                                        i7 = i9;
                                    }
                                }
                                if (b2 == 10) {
                                    gamePaint2 = gamePaint5;
                                } else {
                                    gamePaint2 = gamePaint3;
                                }
                                rect.a = (int) rectF.a;
                                rect.c = (int) rectF.c;
                                rect.b = (int) rectF.b;
                                rect.d = (int) rectF.d;
                                graphicsEngine.a(rect, gamePaint2);
                            } else {
                                byte bClampWorldX = bArr3[i6][i7];
                                if (bClampWorldX == 127) {
                                    bClampWorldX = tileMap.calculateFogNeighborMask(i6, i7, bArr, (byte) 5);
                                    bArr3[i6][i7] = bClampWorldX;
                                }
                                if (bClampWorldX != 0) {
                                    int i10 = bClampWorldX + 128;
                                    if (texture != null) {
                                        TileMap.computeFogAtlasTileRect(i10, rect2);
                                        rect.a((int) (f14 - f9), (int) (f15 - f10), (int) (f16 - f9), (int) (f17 - f10));
                                        graphicsEngine.b(texture, rect2, rect, gamePaint3);
                                    } else if (!tileMap.androidKeyStates[bClampWorldX + 128]) {
                                        GameEngine.log("SmoothFog, missing: " + ((int) bClampWorldX));
                                        tileMap.androidKeyStates[bClampWorldX + 128] = true;
                                    }
                                }
                            }
                            if (b2 != 10) {
                                byte bClampWorldX2 = bArr2[i6][i7];
                                if (bClampWorldX2 == 127) {
                                    bClampWorldX2 = tileMap.calculateFogNeighborMask(i6, i7, bArr, (byte) 10);
                                    bArr2[i6][i7] = bClampWorldX2;
                                }
                                if (bClampWorldX2 != 0) {
                                    int i11 = bClampWorldX2 + 128;
                                    if (texture != null) {
                                        TileMap.computeFogAtlasTileRect(i11, rect2);
                                        rect.a((int) (f14 - f9), (int) (f15 - f10), (int) (f16 - f9), (int) (f17 - f10));
                                        graphicsEngine.b(texture, rect2, rect, gamePaint4);
                                    } else if (!tileMap.androidKeyStates[bClampWorldX2 + 128]) {
                                        GameEngine.log("SmoothFog, missing: " + ((int) bClampWorldX2));
                                        tileMap.androidKeyStates[bClampWorldX2 + 128] = true;
                                    }
                                }
                            }
                        }
                    }
                }
                i7++;
            }
        }
    }

    /* JADX INFO: renamed from: b */
    public void resetLayer() {
        this.tileIds = null;
        this.properties = null;
        this.tileMap = null;
    }

    public MapLayer(TileMap tileMap, String str, int i, int i2) {
        this.tileMap = tileMap;
        initNameAndFlags(str);
        this.widthTiles = i;
        this.heightTiles = i2;
        getTileIds();
    }

    /* JADX INFO: renamed from: a */
    void initNameAndFlags(String str) {
        this.name = str;
        Log.d("RustedWarfare", "MapLayer create: " + str);
        if (str != null) {
            this.lowerName = str.toLowerCase(Locale.ENGLISH);
        }
        this.isItemsLayer = this.lowerName.contains("items");
        this.isGroundLayer = this.lowerName.equalsIgnoreCase("ground");
        if (this.isItemsLayer || this.isGroundLayer) {
            this.isTerrainLayer = true;
        }
        if (str != null && str.equalsIgnoreCase("grounddetails")) {
            this.isTerrainLayer = true;
        }
    }

    public MapLayer(TileMap tileMap, Element element) throws MapLoadException {
        NodeList elementsByTagName;
        this.tileMap = tileMap;
        initNameAndFlags(element.getAttribute("name"));
        this.widthTiles = Short.parseShort(element.getAttribute("width"));
        this.heightTiles = Short.parseShort(element.getAttribute("height"));
        Element element2 = (Element) element.getElementsByTagName("properties").item(0);
        if (element2 != null && (elementsByTagName = element2.getElementsByTagName("property")) != null) {
            this.properties = new Properties();
            for (int i = 0; i < elementsByTagName.getLength(); i++) {
                Element element3 = (Element) elementsByTagName.item(i);
                this.properties.setProperty(element3.getAttribute("name"), element3.getAttribute("value"));
            }
        }
        Element element4 = (Element) element.getElementsByTagName("data").item(0);
        if (element4 == null) {
            throw new MapLoadException("Map is missing <data> element");
        }
        try {
            InputStream inputStreamDecodeCompressedBase64Stream = decodeCompressedBase64Stream(element4.getFirstChild().getNodeValue(), element4.getAttribute("encoding"), element4.getAttribute("compression"));
            BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStreamDecodeCompressedBase64Stream);
            parseTileDataStream(bufferedInputStream);
            if (bufferedInputStream != null) {
                bufferedInputStream.close();
            }
            if (inputStreamDecodeCompressedBase64Stream != null) {
                inputStreamDecodeCompressedBase64Stream.close();
            }
        } catch (IOException e) {
            throw new MapLoadException("Unable to decompress base64 block", e);
        }
    }

    /* JADX INFO: renamed from: a */

    void parseTileDataStream(InputStream inputStream) throws IOException, MapLoadException {
        TileMap b2 = this.tileMap;
        MapTile g2 = null;
        int n2 = -1;
        boolean bl = this.isTerrainLayer;
        HashMap<Integer, MapTile> hashMap = new HashMap<Integer, MapTile>();
        byte[] tileData = readTileData(inputStream);
        int dataOffset = 0;
        for (short s2 = 0; s2 < this.heightTiles; s2 = (short) ((short) (s2 + 1))) {
            for (short s3 = 0; s3 < this.widthTiles; s3 = (short) ((short) (s3 + 1))) {
                int n3 = (tileData[dataOffset] & 255)
                        | ((tileData[dataOffset + 1] & 255) << 8)
                        | ((tileData[dataOffset + 2] & 255) << 16)
                        | ((tileData[dataOffset + 3] & 255) << 24);
                dataOffset += 4;
                boolean bl2 = (n3 & Integer.MIN_VALUE) != 0;
                boolean bl3 = (n3 & 0x40000000) != 0;
                boolean bl4 = (n3 & 0x20000000) != 0;
                n3 &= 0x1FFFFFFF;
                if (bl2 || bl3 || bl4) {
                    // empty if block
                }
                if (n3 == 0) continue;
                if (n2 == n3 && g2 != null) {
                    this.setTileAt(s3, s2, g2, true);
                    continue;
                }
                MapTile g3 = hashMap.get(n3);
                if (g3 != null) {
                    g2 = g3;
                    n2 = n3;
                    this.setTileAt(s3, s2, g2, true);
                    continue;
                }
                Tileset j2 = b2.findTilesetByGlobalTileId(n3);
                if (j2 != null) {
                    g2 = MapTile.createTile(b2, this, j2, n3 - j2.firstGid, s3, s2, bl);
                    if (g2 != null) {
                        this.setTileAt(s3, s2, g2, true);
                        hashMap.put(n3, g2);
                    }
                    n2 = n3;
                    continue;
                }
                throw new MapLoadException("Unable to decode base64 block, could not find tileId: " + n3);
            }
        }
    }

    private byte[] readTileData(InputStream inputStream) throws IOException {
        byte[] tileData = new byte[this.widthTiles * this.heightTiles * 4];
        int bytesRead = 0;
        while (bytesRead < tileData.length) {
            int count = inputStream.read(tileData, bytesRead, tileData.length - bytesRead);
            if (count < 0) {
                throw new IOException("Unexpected end of compressed tile layer");
            }
            if (count == 0) {
                int value = inputStream.read();
                if (value < 0) {
                    throw new IOException("Unexpected end of compressed tile layer");
                }
                tileData[bytesRead++] = (byte) value;
            } else {
                bytesRead += count;
            }
        }
        return tileData;
    }

    /* JADX INFO: renamed from: a */
    public static InputStream decodeCompressedBase64Stream(String str, String str2, String str3) throws MapLoadException {
        InputStream gZIPInputStream;
        if (str2.equals("base64")) {
            byte[] bArrDecodeBase64 = decodeBase64(str.toCharArray());
            if ("gzip".equals(str3)) {
                try {
                    gZIPInputStream = new GZIPInputStream(new ByteArrayInputStream(bArrDecodeBase64));
                } catch (IOException e) {
                    throw new MapLoadException("Unable to decode block in map", e);
                }
            } else if (VariableScope.nullOrMissingString.equals(str3)) {
                gZIPInputStream = new ByteArrayInputStream(bArrDecodeBase64);
            } else if ("zlib".equals(str3)) {
                gZIPInputStream = new InflaterInputStream(new ByteArrayInputStream(bArrDecodeBase64));
            } else {
                throw new MapLoadException("Unsupport tiled map compression: " + str2 + "," + str3 + " (only gzip base64 is supported, this can be set in Tiled's Preferences)");
            }
            return gZIPInputStream;
        }
        throw new MapLoadException("Unsupport tiled map encoding: " + str2 + "," + str3 + " (only gzip base64 is supported, this can be set in Tiled's Preferences)");
    }

    /* JADX INFO: renamed from: a */
    public static byte[] decodeBase64(char[] cArr) {
        int length = cArr.length;
        byte[] bArr = base64DecodeTable;
        for (int i = 0; i < cArr.length; i++) {
            if (cArr[i] > 255 || bArr[cArr[i]] < 0) {
                length--;
            }
        }
        int i2 = (length / 4) * 3;
        if (length % 4 == 3) {
            i2 += 2;
        }
        if (length % 4 == 2) {
            i2++;
        }
        byte[] bArr2 = new byte[i2];
        int i3 = 0;
        int i4 = 0;
        int i5 = 0;
        for (int i6 = 0; i6 < cArr.length; i6++) {
            byte b = cArr[i6] > 255 ? (byte) -1 : bArr[cArr[i6]];
            if (b >= 0) {
                i3 += 6;
                i4 = (i4 << 6) | b;
                if (i3 >= 8) {
                    i3 -= 8;
                    int i7 = i5;
                    i5++;
                    bArr2[i7] = (byte) ((i4 >> i3) & 255);
                }
            }
        }
        if (i5 != bArr2.length) {
            throw new RuntimeException("Data length appears to be wrong (wrote " + i5 + " should be " + bArr2.length + ")");
        }
        return bArr2;
    }
}
