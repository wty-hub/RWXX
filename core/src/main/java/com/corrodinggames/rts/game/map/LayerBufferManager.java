package com.corrodinggames.rts.game.map;

import com.corrodinggames.rts.game.ScorchMark;
import com.corrodinggames.rts.game.units.custom.logicBooleans.VariableScope;
import com.corrodinggames.rts.gameFramework.*;
import com.corrodinggames.rts.gameFramework.graphics.GamePaint;
import com.corrodinggames.rts.gameFramework.graphics.GraphicsEngine;
import com.corrodinggames.rts.gameFramework.graphics.RenderTargetMode;
import com.corrodinggames.rts.gameFramework.graphics.Texture;
import com.corrodinggames.rts.gameFramework.ui.GameUI;
import io.github.rwx.geometry.Rect;
import io.github.rwx.geometry.RectF;
import io.github.rwx.render.canvas.KoolCanvasBlendMode;
import io.github.rwx.render.canvas.KoolGraphicsEngine;
import io.github.rwx.render.canvas.KoolPaint;

import java.util.ArrayList;
import java.util.IdentityHashMap;

/* JADX INFO: renamed from: com.corrodinggames.rts.game.b.c */
/* JADX INFO: loaded from: game-lib.jar:com/corrodinggames/rts/game/b/c.class */
public final class LayerBufferManager {
    private static final boolean TIME_BASED_ZOOM_CACHE_REQUESTED =
            "1".equals(System.getenv("RWX_TIME_BASED_MAP_ZOOM_CACHE"));
    private GraphicsEngine graphicsBackend;
    private boolean smoothFogFadingEnabled;
    private boolean timeBasedZoomCacheEnabled;
    private final ZoomCacheCadence zoomCacheCadence = new ZoomCacheCadence();
    private final ArrayList<LayerBufferCell> offscreenRedrawCandidates = new ArrayList<>();
    private boolean prewarmCameraSampleValid;
    private long prewarmCameraSampleNanos;
    private float prewarmCameraX, prewarmCameraY;
    private double prewarmVelocityX, prewarmVelocityY;

    /* JADX INFO: renamed from: f */
    int gridOriginWorldX;

    /* JADX INFO: renamed from: g */
    int gridOriginWorldY;

    /* JADX INFO: renamed from: h */
    int cellBufferPixelSize;

    /* JADX INFO: renamed from: i */
    int cellWorldExtent;

    /* JADX INFO: renamed from: j */
    int cellInnerBufferPixelSize;

    /* JADX INFO: renamed from: k */
    int cellWorldStepSize;

    /* JADX INFO: renamed from: l */
    float invCellWorldStepSize;

    /* JADX INFO: renamed from: n */
    boolean useFogBlitComposite;

    /* JADX INFO: renamed from: a */
    int gridCellsPerAxis = 7;

    /* JADX INFO: renamed from: b */
    public Texture bufferLayerTexture = null;

    /* JADX INFO: renamed from: c */
    public GraphicsEngine bufferLayerGraphics = null;

    /* JADX INFO: renamed from: d */
    LayerBufferCell[][] gridCells = (LayerBufferCell[][]) null;

    /* JADX INFO: renamed from: e */
    public GamePaint copyBlitPaint = new GamePaint();

    /* JADX INFO: renamed from: m */
    float renderScale = 1.0f;

    /* JADX INFO: renamed from: o */
    Rect tmpRect = new Rect();

    /* JADX INFO: renamed from: p */
    int redrawFrameCounter = 0;

    public void bindGraphicsBackend(GraphicsEngine graphicsEngine) {
        if (graphicsEngine == null || this.graphicsBackend == graphicsEngine) {
            return;
        }
        releaseLayerBuffers();
        this.graphicsBackend = graphicsEngine;
        this.smoothFogFadingEnabled = TileMap.softFogFadingEnabled
                && graphicsEngine.backendCapabilities().getSupportsSmoothFogLayerBuffers();
        this.timeBasedZoomCacheEnabled = TIME_BASED_ZOOM_CACHE_REQUESTED && graphicsEngine instanceof KoolGraphicsEngine;
        if (System.getenv("RWX_FRAME_METRICS") != null || System.getenv("RWX_MAP_CACHE_TRACE") != null) {
            System.out.println("RWXMapZoomCache timeBased=" + this.timeBasedZoomCacheEnabled
                    + " referenceFps=60 stableNanos=" + ZoomCacheCadence.STABLE_NANOS);
        }
    }

    public void releaseLayerBuffers() {
        offscreenRedrawCandidates.clear();
        prewarmCameraSampleValid = false;
        zoomCacheCadence.reset();
        timeBasedZoomCacheEnabled = false;
        GraphicsEngine previousBackend = this.graphicsBackend;
        IdentityHashMap<Texture, Boolean> releasedTextures = new IdentityHashMap<>();
        Texture fallbackTexture = previousBackend != null ? previousBackend.r() : null;
        releaseTarget(this.bufferLayerGraphics, this.bufferLayerTexture, fallbackTexture, releasedTextures);
        if (this.gridCells != null) {
            for (int i = 0; i < this.gridCells.length; i++) {
                LayerBufferCell[] row = this.gridCells[i];
                if (row == null) {
                    continue;
                }
                for (int i2 = 0; i2 < row.length; i2++) {
                    LayerBufferCell layerBufferCell = row[i2];
                    if (layerBufferCell == null) {
                        continue;
                    }
                    releaseTarget(layerBufferCell.cellGraphicsCopy, layerBufferCell.cellLayerTexture, fallbackTexture, releasedTextures);
                    releaseTarget(layerBufferCell.fadeOutGraphics, layerBufferCell.fadeOutTexture, fallbackTexture, releasedTextures);
                }
            }
        }
        this.bufferLayerGraphics = null;
        this.bufferLayerTexture = null;
        this.gridCells = null;
        this.cellBufferPixelSize = 0;
        this.cellInnerBufferPixelSize = 0;
        this.cellWorldExtent = 0;
        this.cellWorldStepSize = 0;
        this.invCellWorldStepSize = 0.0f;
        this.useFogBlitComposite = false;
        this.renderScale = 1.0f;
        this.redrawFrameCounter = 0;
        this.graphicsBackend = null;
        this.smoothFogFadingEnabled = false;
    }

    private static void releaseTarget(
            GraphicsEngine graphicsEngine,
            Texture texture,
            Texture fallbackTexture,
            IdentityHashMap<Texture, Boolean> releasedTextures
    ) {
        if (graphicsEngine != null) {
            graphicsEngine.q();
        }
        if (texture != null && texture != fallbackTexture && releasedTextures.put(texture, Boolean.TRUE) == null) {
            texture.o();
        }
    }

    boolean isSmoothFogFadingEnabled() {
        return this.smoothFogFadingEnabled;
    }

    /**
     * Returns the backend that owns this manager's targets and capability policy. The active
     * render backend may temporarily be a post-processing target; that backend must not change
     * layer-buffer creation or redraw policy.
     */
    GraphicsEngine resourceBackend() {
        if (this.graphicsBackend == null) {
            throw new IllegalStateException("LayerBufferManager graphics backend is not bound");
        }
        return this.graphicsBackend;
    }

    /* JADX INFO: renamed from: a */
    public void updateGridParams() {
        updateGridParams("");
    }

    private void updateGridParams(String resetReason) {
        prewarmCameraSampleValid = false;
        zoomCacheCadence.reset();
        GameEngine gameEngine = GameEngine.getInstance();
        this.renderScale = computeRenderScale();
        if (this.renderScale > 1.0f) {
        }
        this.cellWorldExtent = (int) (this.cellBufferPixelSize / this.renderScale);
        this.cellWorldStepSize = (int) (this.cellInnerBufferPixelSize / this.renderScale);
        this.invCellWorldStepSize = 1.0f / this.cellWorldStepSize;
        this.gridOriginWorldX = gameEngine.viewpointXInt - (this.cellWorldExtent / 2);
        this.gridOriginWorldY = gameEngine.viewpointYInt - (this.cellWorldExtent / 2);
        float f = 1.0f / 20;
        this.gridOriginWorldX = ((int) (this.gridOriginWorldX * f)) * 20;
        this.gridOriginWorldY = ((int) (this.gridOriginWorldY * f)) * 20;
        for (int i = 0; i < this.gridCellsPerAxis; i++) {
            for (int i2 = 0; i2 < this.gridCellsPerAxis; i2++) {
                LayerBufferCell layerBufferCell = this.gridCells[i][i2];
                layerBufferCell.needsRedraw = true;
                layerBufferCell.preRendered = false;
            }
        }
        if (MapCacheTrace.isEnabled()) MapCacheTrace.recordGrid("reset", this, resetReason, 0);
    }

    /* JADX INFO: renamed from: b */
    public void updateCellIndices() {
        for (int i = 0; i < this.gridCellsPerAxis; i++) {
            for (int i2 = 0; i2 < this.gridCellsPerAxis; i2++) {
                LayerBufferCell layerBufferCell = this.gridCells[i][i2];
                layerBufferCell.gridX = i;
                layerBufferCell.gridY = i2;
            }
        }
    }

    /* JADX INFO: renamed from: a */
    public void scrollGridY(int i) {
        LayerBufferCell[] layerBufferCellArr = new LayerBufferCell[this.gridCellsPerAxis];
        if (i > 0) {
            for (int i2 = 0; i2 < this.gridCellsPerAxis; i2++) {
                layerBufferCellArr[i2] = this.gridCells[i2][0];
            }
            for (int i3 = 1; i3 < this.gridCellsPerAxis; i3++) {
                for (int i4 = 0; i4 < this.gridCellsPerAxis; i4++) {
                    this.gridCells[i4][i3 - 1] = this.gridCells[i4][i3];
                }
            }
            for (int i5 = 0; i5 < this.gridCellsPerAxis; i5++) {
                this.gridCells[i5][this.gridCellsPerAxis - 1] = layerBufferCellArr[i5];
            }
            for (int i6 = 0; i6 < this.gridCellsPerAxis; i6++) {
                markReusedCellForRedraw(this.gridCells[i6][this.gridCellsPerAxis - 1]);
            }
        } else {
            for (int i7 = 0; i7 < this.gridCellsPerAxis; i7++) {
                layerBufferCellArr[i7] = this.gridCells[i7][this.gridCellsPerAxis - 1];
            }
            for (int i8 = this.gridCellsPerAxis - 2; i8 >= 0; i8--) {
                for (int i9 = 0; i9 < this.gridCellsPerAxis; i9++) {
                    this.gridCells[i9][i8 + 1] = this.gridCells[i9][i8];
                }
            }
            for (int i10 = 0; i10 < this.gridCellsPerAxis; i10++) {
                this.gridCells[i10][0] = layerBufferCellArr[i10];
            }
            for (int i11 = 0; i11 < this.gridCellsPerAxis; i11++) {
                markReusedCellForRedraw(this.gridCells[i11][0]);
            }
        }
        updateCellIndices();
    }

    /* JADX INFO: renamed from: b */
    public void scrollGridX(int i) {
        LayerBufferCell[] layerBufferCellArr = new LayerBufferCell[this.gridCellsPerAxis];
        if (i > 0) {
            for (int i2 = 0; i2 < this.gridCellsPerAxis; i2++) {
                layerBufferCellArr[i2] = this.gridCells[0][i2];
            }
            for (int i3 = 1; i3 < this.gridCellsPerAxis; i3++) {
                for (int i4 = 0; i4 < this.gridCellsPerAxis; i4++) {
                    this.gridCells[i3 - 1][i4] = this.gridCells[i3][i4];
                }
            }
            for (int i5 = 0; i5 < this.gridCellsPerAxis; i5++) {
                this.gridCells[this.gridCellsPerAxis - 1][i5] = layerBufferCellArr[i5];
            }
            for (int i6 = 0; i6 < this.gridCellsPerAxis; i6++) {
                markReusedCellForRedraw(this.gridCells[this.gridCellsPerAxis - 1][i6]);
            }
        } else {
            for (int i7 = 0; i7 < this.gridCellsPerAxis; i7++) {
                layerBufferCellArr[i7] = this.gridCells[this.gridCellsPerAxis - 1][i7];
            }
            for (int i8 = this.gridCellsPerAxis - 2; i8 >= 0; i8--) {
                for (int i9 = 0; i9 < this.gridCellsPerAxis; i9++) {
                    this.gridCells[i8 + 1][i9] = this.gridCells[i8][i9];
                }
            }
            for (int i10 = 0; i10 < this.gridCellsPerAxis; i10++) {
                this.gridCells[0][i10] = layerBufferCellArr[i10];
            }
            for (int i11 = 0; i11 < this.gridCellsPerAxis; i11++) {
                markReusedCellForRedraw(this.gridCells[0][i11]);
            }
        }
        updateCellIndices();
    }

    private void markReusedCellForRedraw(LayerBufferCell layerBufferCell) {
        layerBufferCell.needsRedraw = true;
        layerBufferCell.preRendered = false;
    }

    private void invalidateCell(LayerBufferCell layerBufferCell, boolean smoothFade) {
        if (smoothFade) {
            layerBufferCell.enableSmoothFade = true;
        } else {
            layerBufferCell.needsRedraw = true;
        }
    }

    /* JADX INFO: renamed from: a */
    public LayerBufferCell getCellAt(int i, int i2) {
        if (i < 0 || i >= this.gridCellsPerAxis || i2 < 0 || i2 >= this.gridCellsPerAxis || this.gridCells == null) {
            return null;
        }
        return this.gridCells[i][i2];
    }

    /* JADX INFO: renamed from: a */
    public void invalidateTileArea(int i, int i2, boolean z) {
        TileMap tileMap = GameEngine.getInstance().tileMap;
        int i3 = tileMap.tileWorldSizeX;
        int i4 = tileMap.tileWorldSizeY;
        int i5 = i * i3;
        int i6 = i2 * i4;
        invalidateWorldRect((i5 - this.gridOriginWorldX) - i3, (i6 - this.gridOriginWorldY) - i4, 3 * i3, 3 * i4, z);
    }

    /* JADX INFO: renamed from: c */
    public void invalidateAllCells() {
        if (this.gridCells != null) {
            for (int i = 0; i < this.gridCellsPerAxis; i++) {
                for (int i2 = 0; i2 < this.gridCellsPerAxis; i2++) {
                    this.gridCells[i][i2].needsRedraw = true;
                }
            }
        }
    }

    public void invalidateFogDisplay() {
        invalidateAllCells();
        if (gridCells == null) return;
        for (LayerBufferCell[] column : gridCells) {
            for (LayerBufferCell cell : column) {
                if (cell == null) continue;
                cell.fadeProgressRatio = 0;
                cell.enableSmoothFade = false;
                cell.fadeFrameCount = 0;
                cell.preRendered = false;
            }
        }
    }

    /* JADX INFO: renamed from: a */
    public void invalidateWorldRect(int i, int i2, int i3, int i4, boolean z) {
        LayerBufferCell cellAt;
        LayerBufferCell cellAt2;
        LayerBufferCell cellAt3;
        int i5 = (int) (i * this.invCellWorldStepSize);
        int i6 = (int) (i2 * this.invCellWorldStepSize);
        LayerBufferCell cellAt4 = getCellAt(i5, i6);
        if (cellAt4 != null) {
            invalidateCell(cellAt4, z);
            boolean z2 = false;
            boolean z3 = false;
            if (i + i3 >= (cellAt4.gridX * this.cellWorldStepSize) + this.cellWorldExtent) {
                z2 = true;
            }
            if (i2 + i4 >= (cellAt4.gridY * this.cellWorldStepSize) + this.cellWorldExtent) {
                z3 = true;
            }
            if (z2 && (cellAt3 = getCellAt(i5 + 1, i6)) != null) {
                invalidateCell(cellAt3, z);
            }
            if (z3 && (cellAt2 = getCellAt(i5, i6 + 1)) != null) {
                invalidateCell(cellAt2, z);
            }
            if (z2 && z3 && (cellAt = getCellAt(i5 + 1, i6 + 1)) != null) {
                invalidateCell(cellAt, z);
            }
        }
    }

    /* JADX INFO: renamed from: a */
    public void applyScorchToCells(ScorchMark scorchMark) {
        RectF rectFC = scorchMark.c();
        for (int i = 0; i < this.gridCellsPerAxis; i++) {
            for (int i2 = 0; i2 < this.gridCellsPerAxis; i2++) {
                if (this.gridCells != null) {
                    LayerBufferCell layerBufferCell = this.gridCells[i][i2];
                    if (Utility.rectanglesOverlap(layerBufferCell.getWorldBoundsRect(), rectFC)) {
                        boolean z = this.renderScale != 1.0f;
                        if (z) {
                        }
                        scorchMark.a(layerBufferCell.cellGraphicsCopy, layerBufferCell.getWorldLeft(), layerBufferCell.getWorldTop(), this.renderScale);
                        layerBufferCell.cellGraphicsCopy.p();
                        if (z) {
                        }
                    }
                }
            }
        }
    }

    /* JADX INFO: renamed from: a */
    public void renderScorchMarksInCell(int i, int i2, GraphicsEngine graphicsEngine) {
        LayerBufferCell layerBufferCell = this.gridCells[i][i2];
        boolean z = this.renderScale != 1.0f;
        if (z) {
        }
        Rect worldBoundsRect = layerBufferCell.getWorldBoundsRect();
        Utility.grow(worldBoundsRect, 95.0f);
        GameObject[] gameObjectArrA = GameObject.fastGameObjectList.a();
        int size = GameObject.fastGameObjectList.size();
        for (int i3 = 0; i3 < size; i3++) {
            GameObject gameObject = gameObjectArrA[i3];
            if (gameObject instanceof ScorchMark) {
                ScorchMark scorchMark = (ScorchMark) gameObject;
                if (worldBoundsRect.b((int) scorchMark.posX, (int) scorchMark.posY)) {
                    scorchMark.a(graphicsEngine, layerBufferCell.getWorldLeft(), layerBufferCell.getWorldTop(), this.renderScale);
                }
            }
        }
        if (z) {
        }
    }

    /* JADX INFO: renamed from: b */
    public void drawDebugCellOverlay(int i, int i2, GraphicsEngine graphicsEngine) {
        GameEngine gameEngine = GameEngine.getInstance();
        TileMap tileMap = gameEngine.tileMap;
        if (gameEngine.gameUI.returnsFalse()) {
            int i3 = this.gridOriginWorldX + (i * this.cellWorldStepSize);
            int i4 = this.gridOriginWorldY + (i2 * this.cellWorldStepSize);
            int i5 = this.cellWorldExtent;
            int i6 = this.cellWorldExtent;
            int i7 = tileMap.groundLayer.widthTiles;
            int i8 = tileMap.groundLayer.heightTiles;
            if (((int) (i3 * tileMap.tileScaleX)) < 0) {
            }
            if (((int) (i4 * tileMap.tileScaleY)) < 0) {
            }
            if (((int) ((i3 + i5) * tileMap.tileScaleX)) > i7 - 1) {
                int i9 = i7 - 1;
            }
            if (((int) ((i4 + i6) * tileMap.tileScaleY)) > i8 - 1) {
                int i10 = i8 - 1;
            }
            if (this.renderScale < 0.4d) {
                return;
            }
            boolean z = this.renderScale != 1.0f;
            if (z) {
                graphicsEngine.i();
                graphicsEngine.a(this.renderScale, this.renderScale);
            }
            if (z) {
                graphicsEngine.j();
            }
        }
    }

    /* JADX INFO: renamed from: b */
    public void preRenderCell(int i, int i2) {
        LayerBufferCell layerBufferCell = TileMap.layerBufferManager.gridCells[i][i2];
        GameEngine gameEngine = GameEngine.getInstance();
        TileMap tileMap = gameEngine.tileMap;
        layerBufferCell.preRendered = true;
        boolean z = false;
        GraphicsEngine graphicsEngine = this.bufferLayerGraphics;
        graphicsEngine.b(-16777216);
        Texture texture = gameEngine.minimap.unitsTexture;
        if (texture != null) {
            Rect rect = new Rect();
            RectF rectF = new RectF();
            setMinimapSourceRect(
                    rect,
                    this.gridOriginWorldX + (i * this.cellWorldStepSize),
                    this.gridOriginWorldY + (i2 * this.cellWorldStepSize),
                    this.gridOriginWorldX + ((i + 1) * this.cellWorldStepSize),
                    this.gridOriginWorldY + ((i2 + 1) * this.cellWorldStepSize),
                    tileMap.tileWorldSizeX * tileMap.tileCountX,
                    tileMap.tileWorldSizeY * tileMap.tileCountY,
                    texture.p,
                    texture.q
            );
            rectF.a(0.0f, 0.0f, this.cellBufferPixelSize, this.cellBufferPixelSize);
            graphicsEngine.a(texture, rect, rectF, this.copyBlitPaint);
        }
        graphicsEngine.p();
        if (!z) {
            if (resourceBackend().backendCapabilities().getClearLayerBuffersBeforeCopy()) {
                layerBufferCell.cellGraphicsCopy.a(0, KoolCanvasBlendMode.Clear);
            }
            layerBufferCell.cellGraphicsCopy.b(this.bufferLayerTexture, 0.0f, 0.0f, (KoolPaint) null);
            layerBufferCell.cellGraphicsCopy.p();
        }
    }

    static void setMinimapSourceRect(Rect rect, int worldLeft, int worldTop, int worldRight, int worldBottom, int mapWorldWidth, int mapWorldHeight, int textureWidth, int textureHeight) {
        rect.a(
                (int) ((((float) worldLeft) / ((float) mapWorldWidth)) * textureWidth),
                (int) ((((float) worldTop) / ((float) mapWorldHeight)) * textureHeight),
                (int) ((((float) worldRight) / ((float) mapWorldWidth)) * textureWidth),
                (int) ((((float) worldBottom) / ((float) mapWorldHeight)) * textureHeight)
        );
    }

    /* JADX INFO: renamed from: c */
    public void renderCell(int i, int i2) {
        LayerBufferCell layerBufferCell = TileMap.layerBufferManager.gridCells[i][i2];
        boolean direct = resourceBackend().prefersDirectLayerBufferRendering();
        GraphicsEngine target = direct ? layerBufferCell.cellGraphicsCopy : this.bufferLayerGraphics;
        try {
            renderCellInto(i, i2, target, direct);
        } finally {
            if (direct) target.f();
        }
    }

    /* JADX INFO: renamed from: c */
    public void renderCellInto(int i, int i2, GraphicsEngine graphicsEngine) {
        renderCellInto(i, i2, graphicsEngine, false);
    }

    private void renderCellInto(int i, int i2, GraphicsEngine graphicsEngine, boolean directToCellTexture) {
        if (!MapCacheTrace.isEnabled()) {
            renderCellContents(i, i2, graphicsEngine, directToCellTexture);
            return;
        }
        MapCacheTrace.CellSpan span = MapCacheTrace.beginCell(this, i, i2);
        boolean completed = false;
        try {
            renderCellContents(i, i2, graphicsEngine, directToCellTexture);
            completed = true;
        } finally {
            MapCacheTrace.endCell(span, completed);
        }
    }

    private void renderCellContents(int i, int i2, GraphicsEngine graphicsEngine, boolean directToCellTexture) {
        LayerBufferCell layerBufferCell = TileMap.layerBufferManager.gridCells[i][i2];
        GameEngine gameEngine = GameEngine.getInstance();
        TileMap tileMap = gameEngine.tileMap;
        boolean z = false;
        if (gameEngine.settingsEngine.renderFancyWater) {
            z = true;
        }
        if (GameEngine.isSpaceGame() || GameEngine.isMapDebugMode()) {
            z = true;
        }
        if (z) {
            graphicsEngine.a(0, KoolCanvasBlendMode.Clear);
        } else {
            boolean z2 = false;
            if (GameEngine.isSpaceGame()) {
                z2 = true;
            }
            if (resourceBackend().backendCapabilities().getClearLayerBuffersBeforeCopy()) {
                z2 = true;
            }
            if (GameUI.bO) {
            }
            if (tileMap.isFogDisplayEnabled()) {
            }
            if (z2) {
                graphicsEngine.b(-16777216);
            }
        }
        if (resourceBackend().backendCapabilities().getClearLayerBuffersBeforeCopy()) {
            graphicsEngine.a(0, KoolCanvasBlendMode.Clear);
        }
        int i3 = this.gridOriginWorldX + (i * this.cellWorldStepSize);
        int i4 = this.gridOriginWorldY + (i2 * this.cellWorldStepSize);
        boolean z3 = false;
        boolean z4 = false;
        if (!tileMap.groundLayer.hasAlpha) {
            z3 = true;
        }
        if (tileMap.isFogDisplayEnabled()) {
            z4 = true;
        }
        if (TileMap.fogDebugGlobalFlag) {
            z3 = false;
            z4 = false;
        }
        if (z3) {
            graphicsEngine.a(true);
        }
        tileMap.groundLayer.renderLayerRegion(graphicsEngine, i3, i4, i3, i4, this.cellWorldExtent, this.cellWorldExtent, this.renderScale, this.renderScale, tileMap.isFogDisplayEnabled(), false, false);
        if (tileMap.groundDetailsLayer != null) {
            if (z3 && tileMap.groundDetailsLayer.hasAlpha) {
                graphicsEngine.f();
                GameEngine.log("Ending blit early");
            }
            tileMap.groundDetailsLayer.renderLayerRegion(graphicsEngine, i3, i4, i3, i4, this.cellWorldExtent, this.cellWorldExtent, this.renderScale, this.renderScale, tileMap.isFogDisplayEnabled(), false, false);
        }
        if (tileMap.groundDetails2Layer != null) {
            if (z3 && tileMap.groundDetails2Layer.hasAlpha) {
                graphicsEngine.f();
                GameEngine.log("Ending blit early");
            }
            tileMap.groundDetails2Layer.renderLayerRegion(graphicsEngine, i3, i4, i3, i4, this.cellWorldExtent, this.cellWorldExtent, this.renderScale, this.renderScale, tileMap.isFogDisplayEnabled(), false, false);
        }
        for (MapLayer mapLayer : tileMap.mapLayers) {
            if (mapLayer.isItemsLayer) {
                if (z3 && mapLayer.hasAlpha) {
                    graphicsEngine.f();
                    GameEngine.log("Ending blit early");
                }
                mapLayer.renderLayerRegion(graphicsEngine, i3, i4, i3, i4, this.cellWorldExtent, this.cellWorldExtent, this.renderScale, this.renderScale, tileMap.isFogDisplayEnabled(), false, false);
            }
        }
        renderScorchMarksInCell(i, i2, graphicsEngine);
        if (tileMap.isFogDisplayEnabled()) {
            if (z4) {
                graphicsEngine.a(false);
            }
            tileMap.groundLayer.renderLayerRegion(graphicsEngine, i3, i4, i3, i4, this.cellWorldExtent, this.cellWorldExtent, this.renderScale, this.renderScale, tileMap.isFogDisplayEnabled(), true, true);
        }
        if (z3 || z4) {
            graphicsEngine.f();
        }
        if (gameEngine.gameUI.returnsFalse()) {
            drawDebugCellOverlay(i, i2, graphicsEngine);
        }
        layerBufferCell.needsRedraw = false;
        layerBufferCell.enableSmoothFade = false;
        layerBufferCell.fadeFrameCount = 0;
        layerBufferCell.preRendered = false;
        graphicsEngine.p();
        if (!directToCellTexture) {
            if (z || resourceBackend().backendCapabilities().getClearLayerBuffersBeforeCopy()) {
                layerBufferCell.cellGraphicsCopy.a(0, KoolCanvasBlendMode.Clear);
            }
            layerBufferCell.cellGraphicsCopy.b(this.bufferLayerTexture, 0.0f, 0.0f, (KoolPaint) null);
            layerBufferCell.cellGraphicsCopy.p();
        }
        if (TileMap.c) {
            layerBufferCell.cellGraphicsCopy.a(VariableScope.nullOrMissingString + layerBufferCell.redrawVersion, 40.0f, 40.0f, TileMap.fogAtlasDebugRedStrokePaint);
        }
        layerBufferCell.redrawVersion++;
    }

    /* JADX INFO: renamed from: d */
    public void update() {
        if (GameEngine.isNonAndroidVersion && !GameEngine.isJavaDesktopVersion && !GameEngine.isGDXVersion) {
            return;
        }
        GameEngine gameEngine = GameEngine.getInstance();
        if (this.graphicsBackend == null) {
            bindGraphicsBackend(gameEngine.renderGraphicsEngine);
        }
        int iMax = Math.max((int) gameEngine.currentScreenWidthPixels, (int) gameEngine.currentScreenHeightPixels) + 3;
        if (this.gridCells != null) {
            int requiredCells = this.gridCellsPerAxis;
            int fixedSize = resourceBackend().backendCapabilities().getFixedLayerBufferPixelSize();
            if (fixedSize > 0) {
                requiredCells = fixedLayerBufferGridCells(this.gridCellsPerAxis, iMax, fixedSize,
                        resourceBackend().backendCapabilities().getExtraLayerBufferCells());
            } else if (this.cellBufferPixelSize * this.gridCellsPerAxis < iMax + this.cellBufferPixelSize + 1) {
                requiredCells++;
            }
            if (requiredCells > this.gridCellsPerAxis) {
                GameEngine.log("map", "screen must have changed size, layerBufferSize too small at " + this.gridCellsPerAxis + ", adding to LayerBitmapBuffer");
                GameEngine.log("map", "new viewpoint:" + gameEngine.currentScreenWidthPixels + ", " + gameEngine.currentScreenHeightPixels);
                resizeBufferGrid(requiredCells);
            }
        }
        if (this.gridCells == null) {
            // TileMap initializes the user preference after the manager is bound during engine startup.
            this.smoothFogFadingEnabled = TileMap.softFogFadingEnabled
                    && resourceBackend().backendCapabilities().getSupportsSmoothFogLayerBuffers();
            GameEngine.log("map", "setupLayerBuffers for size:" + iMax);
            long jNanoTime = System.nanoTime();
            GraphicsEngine resourceBackend = resourceBackend();
            int fixedLayerBufferPixelSize = resourceBackend
                    .backendCapabilities()
                    .getFixedLayerBufferPixelSize();
            boolean z = fixedLayerBufferPixelSize > 0;
            if (z) {
                this.cellBufferPixelSize = fixedLayerBufferPixelSize;
                this.gridCellsPerAxis = fixedLayerBufferGridCells(0, iMax, this.cellBufferPixelSize,
                        resourceBackend.backendCapabilities().getExtraLayerBufferCells());
            } else {
                iMax = Math.max(600, iMax);
                this.cellBufferPixelSize = (iMax / (this.gridCellsPerAxis - 2)) + 7 + 4;
                this.cellBufferPixelSize = ((int) ((this.cellBufferPixelSize * (1.0f / 20)) + 0.5f)) * 20;
            }
            if (this.cellBufferPixelSize * this.gridCellsPerAxis < iMax + this.cellBufferPixelSize + 1) {
                GameEngine.logColored("layerBufferSize is too small");
                GameEngine.logColored("layerBufferCount:" + this.gridCellsPerAxis);
                GameEngine.logColored("(layerBufferSize*(layerBufferCount):" + (this.cellBufferPixelSize * this.gridCellsPerAxis));
                GameEngine.logColored("longest+layerBufferSize+1:" + (iMax + this.cellBufferPixelSize + 1));
                GameEngine.logColored("longest:" + iMax);
                if (z) {
                    this.gridCellsPerAxis++;
                } else {
                    this.cellBufferPixelSize += 100;
                }
            }
            GameEngine.log("layerBufferSize:" + this.cellBufferPixelSize);
            this.cellInnerBufferPixelSize = this.cellBufferPixelSize - 4;
            GameEngine.logColored("layerBuffer:" + this.gridCellsPerAxis + "x" + this.gridCellsPerAxis + " = " + (this.gridCellsPerAxis * this.gridCellsPerAxis) + (this.smoothFogFadingEnabled ? " x2 for soft fade " : VariableScope.nullOrMissingString));
            this.gridCells = new LayerBufferCell[this.gridCellsPerAxis][this.gridCellsPerAxis];
            z = false;
            if (gameEngine.settingsEngine.renderFancyWater) {
                z = true;
            }
            if (GameEngine.isSpaceGame() || GameEngine.isMapDebugMode()) {
                z = true;
            }
            if (this.cellBufferPixelSize <= 0) {
                GameEngine.logColored("layerBuffer buffer size was too small at: " + this.cellBufferPixelSize);
                this.cellBufferPixelSize = 512;
            }
            if (z) {
                this.bufferLayerTexture = resourceBackend.a(this.cellBufferPixelSize, this.cellBufferPixelSize, true);
            } else {
                this.bufferLayerTexture = resourceBackend.a(this.cellBufferPixelSize, this.cellBufferPixelSize, false);
            }
            this.bufferLayerTexture.b(true);
            this.bufferLayerGraphics = resourceBackend.b(this.bufferLayerTexture, RenderTargetMode.IMMEDIATE);
            initMissingLayerBufferImages();
            GameEngine.log("----- layerBuffers create in:" + ((System.nanoTime() - jNanoTime) / 1000000.0d) + " ms");
        }
    }

    /** New and existing fixed-size grids need the same coverage and backend preload cells. */
    static int fixedLayerBufferGridCells(int currentCells, int longestPixelsWithPadding, int fixedPixelSize, int extraCells) {
        return Math.max(currentCells, (longestPixelsWithPadding / fixedPixelSize) + 2 + Math.max(0, extraCells));
    }

    /** Grow storage without replacing the existing targets; allocation and invalidation follow. */
    boolean growGridStorage(int i) {
        if (i <= this.gridCellsPerAxis) return false;
        LayerBufferCell[][] layerBufferCellArr = new LayerBufferCell[i][i];
        for (int i2 = 0; i2 < this.gridCellsPerAxis; i2++) {
            for (int i3 = 0; i3 < this.gridCellsPerAxis; i3++) {
                layerBufferCellArr[i2][i3] = this.gridCells[i2][i3];
            }
        }
        this.gridCells = layerBufferCellArr;
        this.gridCellsPerAxis = i;
        return true;
    }

    /* JADX INFO: renamed from: c */
    public void resizeBufferGrid(int i) {
        if (i < this.gridCellsPerAxis) {
            GameEngine.logWarningAndStack("newLayerBufferCount:" + i);
            return;
        }
        growGridStorage(i);
        initMissingLayerBufferImages();
    }

    /* JADX INFO: renamed from: e */
    public void disableSmoothFogFading() {
        this.smoothFogFadingEnabled = false;
        for (int i = 0; i < this.gridCellsPerAxis; i++) {
            for (int i2 = 0; i2 < this.gridCellsPerAxis; i2++) {
                LayerBufferCell layerBufferCell = this.gridCells[i][i2];
                if (layerBufferCell != null) {
                    if (layerBufferCell.fadeOutGraphics != null) {
                        layerBufferCell.fadeOutGraphics.q();
                        layerBufferCell.fadeOutGraphics = null;
                    }
                    if (layerBufferCell.fadeOutTexture != null) {
                        layerBufferCell.fadeOutTexture.o();
                        layerBufferCell.fadeOutTexture = null;
                    }
                }
            }
        }
    }

    /* JADX INFO: renamed from: f */
    public void initMissingLayerBufferImages() {
        GameEngine gameEngine = GameEngine.getInstance();
        GraphicsEngine resourceBackend = resourceBackend();
        ArrayList<LayerBufferCell> arrayList = null;
        boolean z = false;
        for (int i = 0; i < this.gridCellsPerAxis; i++) {
            for (int i2 = 0; i2 < this.gridCellsPerAxis; i2++) {
                if (this.gridCells[i][i2] == null) {
                    LayerBufferCell layerBufferCell = new LayerBufferCell(this, i, i2);
                    layerBufferCell.gridLinearIndex = this.redrawFrameCounter;
                    this.redrawFrameCounter++;
                    this.gridCells[i][i2] = layerBufferCell;
                    if (this.cellBufferPixelSize <= 0) {
                        GameEngine.logColored("initMissingLayerBufferImages: layerBuffer buffer size was too small at: " + this.cellBufferPixelSize);
                        this.cellBufferPixelSize = 512;
                    }
                    if (z) {
                        layerBufferCell.cellLayerTexture = resourceBackend.r();
                    } else if (gameEngine.settingsEngine.renderFancyWater) {
                        layerBufferCell.cellLayerTexture = resourceBackend.a(this.cellBufferPixelSize, this.cellBufferPixelSize, true);
                    } else {
                        layerBufferCell.cellLayerTexture = resourceBackend.a(this.cellBufferPixelSize, this.cellBufferPixelSize, false);
                    }
                    layerBufferCell.cellLayerTexture.b(true);
                    if (layerBufferCell.cellLayerTexture.A()) {
                        if (!z) {
                            GameEngine.logColored("initMissingLayerBufferImages: Failed to create map buffer at :" + this.cellBufferPixelSize + "px");
                        }
                        z = true;
                        layerBufferCell.cellLayerTexture = resourceBackend.r();
                        layerBufferCell.cellLayerTexture.b(true);
                    }
                    try {
                        layerBufferCell.cellGraphicsCopy = resourceBackend.b(layerBufferCell.cellLayerTexture, RenderTargetMode.IMMEDIATE);
                    } catch (OutOfMemoryError e) {
                        if (!z) {
                            GameEngine.reportOOM(AssetType.gameImageCreate, e);
                        }
                        z = true;
                        layerBufferCell.cellLayerTexture = resourceBackend.r();
                        layerBufferCell.cellLayerTexture.b(true);
                        layerBufferCell.cellGraphicsCopy = resourceBackend.b(layerBufferCell.cellLayerTexture, RenderTargetMode.IMMEDIATE);
                    }
                    if (arrayList == null) {
                        arrayList = new ArrayList();
                    }
                    arrayList.add(layerBufferCell);
                }
            }
        }
        if (z && this.smoothFogFadingEnabled) {
            disableSmoothFogFading();
        }
        if (arrayList != null) {
            for (LayerBufferCell layerBufferCell2 : arrayList) {
                if (this.smoothFogFadingEnabled) {
                    try {
                        layerBufferCell2.initFadeBufferTexture();
                    } catch (OutOfMemoryError e2) {
                        disableSmoothFogFading();
                        GameEngine.logColored("Not enough free memory to enable smooth fog fading");
                        System.gc();
                    }
                }
            }
        }
        updateGridParams();
    }

    /* JADX INFO: renamed from: g */
    public float computeRenderScale() {
        GameEngine gameEngine = GameEngine.getInstance();
        if (gameEngine.zoom > 1.0f) {
            return 1.0f;
        }
        return gameEngine.zoom;
    }

    public boolean hasVisiblePendingRedraws() {
        if (this.gridCells == null) {
            return true;
        }
        for (int i = 0; i < this.gridCellsPerAxis; i++) {
            for (int i2 = 0; i2 < this.gridCellsPerAxis; i2++) {
                LayerBufferCell layerBufferCell = this.gridCells[i][i2];
                if (layerBufferCell == null) {
                    return true;
                }
                if (!layerBufferCell.screenDstRect.a()) {
                    if (layerBufferCell.needsRedraw || layerBufferCell.enableSmoothFade) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public void renderVisiblePendingRedrawsNow() {
        renderVisiblePendingRedraws(Integer.MAX_VALUE);
    }

    public int renderVisiblePendingRedraws(int budgetMs) {
        return renderPendingRedraws(budgetMs, true);
    }

    public int renderPendingRedraws(int budgetMs) {
        return renderPendingRedraws(budgetMs, false);
    }

    /** Prepare recycled terrain cells before they enter the viewport; preserve visible fog cadence. */
    public int renderOffscreenPendingRedraws(int budgetMs) {
        if (this.gridCells == null || budgetMs <= 0) return 0;
        GameEngine engine = GameEngine.getInstance();
        long now = System.nanoTime();
        long deadlineNanos = budgetMs == Integer.MAX_VALUE ? Long.MAX_VALUE : now + budgetMs * 1000000L;
        // This is called after publishing the frame, so sample the final clamped camera once.
        // Resets/zoom changes discard the previous sample; stopping/reversing only changes order.
        double elapsedSeconds = (now - prewarmCameraSampleNanos) / 1_000_000_000.0;
        prewarmVelocityX = prewarmCameraSampleValid && elapsedSeconds > 0
                ? (engine.viewpointX - prewarmCameraX) / elapsedSeconds : 0;
        prewarmVelocityY = prewarmCameraSampleValid && elapsedSeconds > 0
                ? (engine.viewpointY - prewarmCameraY) / elapsedSeconds : 0;
        if (!Double.isFinite(prewarmVelocityX)) prewarmVelocityX = 0;
        if (!Double.isFinite(prewarmVelocityY)) prewarmVelocityY = 0;
        prewarmCameraX = engine.viewpointX;
        prewarmCameraY = engine.viewpointY;
        prewarmCameraSampleNanos = now;
        prewarmCameraSampleValid = true;
        offscreenRedrawCandidates.clear();
        for (int i = 0; i < this.gridCellsPerAxis; i++) {
            for (int j = 0; j < this.gridCellsPerAxis; j++) {
                LayerBufferCell cell = this.gridCells[i][j];
                if (cell == null || !cell.screenDstRect.a() || !cell.needsRedraw) continue;
                long left = this.gridOriginWorldX + (long) i * this.cellWorldStepSize;
                long top = this.gridOriginWorldY + (long) j * this.cellWorldStepSize;
                if (left >= engine.tileMap.getWorldWidth() || top >= engine.tileMap.getWorldHeight()
                        || left + this.cellWorldExtent <= 0 || top + this.cellWorldExtent <= 0) continue;
                offscreenRedrawCandidates.add(cell);
            }
        }
        // Stable insertion sort avoids a temporary sort array for these small reusable queues.
        for (int i = 1; i < offscreenRedrawCandidates.size(); i++) {
            LayerBufferCell cell = offscreenRedrawCandidates.get(i);
            double entry = prewarmEntryTime(cell, engine);
            double distance = prewarmViewportDistance(cell, engine);
            int insertion = i;
            while (insertion > 0) {
                LayerBufferCell previous = offscreenRedrawCandidates.get(insertion - 1);
                if (comparePrewarmPriority(entry, distance, prewarmEntryTime(previous, engine),
                        prewarmViewportDistance(previous, engine)) >= 0) break;
                offscreenRedrawCandidates.set(insertion, previous);
                insertion--;
            }
            offscreenRedrawCandidates.set(insertion, cell);
        }
        int renderedCount = 0;
        boolean lockedFogAtlas = false;
        try {
            for (int i = 0; i < offscreenRedrawCandidates.size(); i++) {
                LayerBufferCell cell = offscreenRedrawCandidates.get(i);
                if (renderedCount > 0 && System.nanoTime() >= deadlineNanos) break;
                if (resourceBackend().backendCapabilities().getRequiresFogAtlasLock() && !lockedFogAtlas) {
                    TileMap.acquireFogAtlasLock();
                    lockedFogAtlas = true;
                }
                engine.renderGraphicsEngine.i();
                renderCell(cell.gridX, cell.gridY);
                engine.renderGraphicsEngine.j();
                renderedCount++;
            }
            return renderedCount;
        } finally {
            if (lockedFogAtlas) TileMap.releaseFogAtlasLock();
            offscreenRedrawCandidates.clear();
        }
    }

    private double prewarmEntryTime(LayerBufferCell cell, GameEngine engine) {
        double left = this.gridOriginWorldX + (long) cell.gridX * this.cellWorldStepSize;
        double top = this.gridOriginWorldY + (long) cell.gridY * this.cellWorldStepSize;
        return offscreenEntryTime(left, top, left + this.cellWorldExtent, top + this.cellWorldExtent,
                engine.viewpointXSnapped, engine.viewpointYSnapped, engine.visibleWorldWidth,
                engine.visibleWorldHeight, prewarmVelocityX, prewarmVelocityY);
    }

    private double prewarmViewportDistance(LayerBufferCell cell, GameEngine engine) {
        double left = this.gridOriginWorldX + (long) cell.gridX * this.cellWorldStepSize;
        double top = this.gridOriginWorldY + (long) cell.gridY * this.cellWorldStepSize;
        return offscreenViewportDistanceSquared(left, top, left + this.cellWorldExtent,
                top + this.cellWorldExtent, engine.viewpointXSnapped, engine.viewpointYSnapped,
                engine.visibleWorldWidth, engine.visibleWorldHeight);
    }

    /** Slab intersection time; infinity means the current camera trajectory misses the cell. */
    static double offscreenEntryTime(double left, double top, double right, double bottom,
                                    double cameraX, double cameraY, double width, double height,
                                    double velocityX, double velocityY) {
        if (!Double.isFinite(velocityX) || !Double.isFinite(velocityY)) return Double.POSITIVE_INFINITY;
        double entry = 0, exit = Double.POSITIVE_INFINITY;
        if (velocityX == 0) {
            if (right < cameraX || left > cameraX + width) return Double.POSITIVE_INFINITY;
        } else {
            double a = (left - cameraX - width) / velocityX;
            double b = (right - cameraX) / velocityX;
            entry = Math.max(entry, Math.min(a, b));
            exit = Math.min(exit, Math.max(a, b));
        }
        if (velocityY == 0) {
            if (bottom < cameraY || top > cameraY + height) return Double.POSITIVE_INFINITY;
        } else {
            double a = (top - cameraY - height) / velocityY;
            double b = (bottom - cameraY) / velocityY;
            entry = Math.max(entry, Math.min(a, b));
            exit = Math.min(exit, Math.max(a, b));
        }
        return entry <= exit ? entry : Double.POSITIVE_INFINITY;
    }

    static double offscreenViewportDistanceSquared(double left, double top, double right, double bottom,
                                                   double cameraX, double cameraY, double width, double height) {
        double dx = Math.max(0, Math.max(cameraX - right, left - cameraX - width));
        double dy = Math.max(0, Math.max(cameraY - bottom, top - cameraY - height));
        return dx * dx + dy * dy;
    }

    static int comparePrewarmPriority(double entryA, double distanceA, double entryB, double distanceB) {
        int timeOrder = Double.compare(entryA, entryB);
        return timeOrder != 0 ? timeOrder : Double.compare(distanceA, distanceB);
    }

    private int renderPendingRedraws(int budgetMs, boolean visibleOnly) {
        return renderPendingRedraws(budgetMs, visibleOnly, false);
    }

    private int renderPendingRedraws(int budgetMs, boolean visibleOnly, boolean offscreenOnly) {
        if (this.gridCells == null) {
            return 0;
        }
        if (budgetMs <= 0) {
            return 0;
        }
        GameEngine gameEngine = GameEngine.getInstance();
        long deadlineNanos = budgetMs == Integer.MAX_VALUE ? Long.MAX_VALUE : System.nanoTime() + (budgetMs * 1000000L);
        int renderedCount = 0;
        boolean lockedFogAtlas = false;
        try {
            for (int i = 0; i < this.gridCellsPerAxis; i++) {
                for (int i2 = 0; i2 < this.gridCellsPerAxis; i2++) {
                    LayerBufferCell layerBufferCell = this.gridCells[i][i2];
                    if (layerBufferCell == null || (visibleOnly && layerBufferCell.screenDstRect.a())
                            || (offscreenOnly && !layerBufferCell.screenDstRect.a())) {
                        continue;
                    }
                    if (!layerBufferCell.needsRedraw && (offscreenOnly || !layerBufferCell.enableSmoothFade)) {
                        continue;
                    }
                    if (offscreenOnly) {
                        long left = this.gridOriginWorldX + (long) i * this.cellWorldStepSize;
                        long top = this.gridOriginWorldY + (long) i2 * this.cellWorldStepSize;
                        if (left >= gameEngine.tileMap.getWorldWidth() || top >= gameEngine.tileMap.getWorldHeight()
                                || left + this.cellWorldExtent <= 0 || top + this.cellWorldExtent <= 0) {
                            continue;
                        }
                    }
                    if (renderedCount > 0 && System.nanoTime() >= deadlineNanos) {
                        return renderedCount;
                    }
                    if (resourceBackend().backendCapabilities().getRequiresFogAtlasLock()
                            && !lockedFogAtlas) {
                        TileMap.acquireFogAtlasLock();
                        lockedFogAtlas = true;
                    }
                    gameEngine.renderGraphicsEngine.i();
                    renderCell(i, i2);
                    gameEngine.renderGraphicsEngine.j();
                    renderedCount++;
                }
            }
            return renderedCount;
        } finally {
            if (lockedFogAtlas) {
                TileMap.releaseFogAtlasLock();
            }
        }
    }

    static int boundedScrollPreloadMargin(int requested, int cells, int step, float visibleExtent) {
        if (requested <= 0) return 0;
        // Leave enough room that scrolling one way cannot immediately trigger the opposite check.
        double spare = (cells - 1L) * step - Math.ceil(visibleExtent) - 5.0;
        return (int) Math.min(requested, Math.max(0.0, Math.floor(spare / 2.0)));
    }

    /** Keep the overlapping cells on their existing world coordinates after bounded large pans. */
    boolean scrollGridToViewport(int cameraX, int cameraY, float width, float height, int preloadX, int preloadY) {
        int limit = Math.max(0, this.gridCellsPerAxis - 1);
        long span = (long) this.gridCellsPerAxis * this.cellWorldStepSize;
        double right = (double) cameraX + width + 4 + preloadX;
        long left = (long) cameraX - 1 - preloadX;
        double bottom = (double) cameraY + height + 4 + preloadY;
        long top = (long) cameraY - 1 - preloadY;
        int moved = 0;
        if (right > this.gridOriginWorldX + span) {
            while (right > this.gridOriginWorldX + span && moved < limit) {
                this.gridOriginWorldX += this.cellWorldStepSize;
                scrollGridX(1);
                if (MapCacheTrace.isEnabled()) MapCacheTrace.recordGrid("scroll", this, "x", 1);
                moved++;
            }
        } else if (left < this.gridOriginWorldX) {
            while (left < this.gridOriginWorldX && moved < limit) {
                this.gridOriginWorldX -= this.cellWorldStepSize;
                scrollGridX(-1);
                if (MapCacheTrace.isEnabled()) MapCacheTrace.recordGrid("scroll", this, "x", -1);
                moved++;
            }
        }
        moved = 0;
        if (bottom > this.gridOriginWorldY + span) {
            while (bottom > this.gridOriginWorldY + span && moved < limit) {
                this.gridOriginWorldY += this.cellWorldStepSize;
                scrollGridY(1);
                if (MapCacheTrace.isEnabled()) MapCacheTrace.recordGrid("scroll", this, "y", 1);
                moved++;
            }
        } else if (top < this.gridOriginWorldY) {
            while (top < this.gridOriginWorldY && moved < limit) {
                this.gridOriginWorldY -= this.cellWorldStepSize;
                scrollGridY(-1);
                if (MapCacheTrace.isEnabled()) MapCacheTrace.recordGrid("scroll", this, "y", -1);
                moved++;
            }
        }
        // No wrapping by an entire grid: that has no overlap worth preserving. The caller resets.
        return left >= this.gridOriginWorldX && right <= this.gridOriginWorldX + span
                && top >= this.gridOriginWorldY && bottom <= this.gridOriginWorldY + span;
    }

    /* JADX INFO: renamed from: a */
    public void setRenderScale(float f) {
        GameEngine gameEngine = GameEngine.getInstance();
        TileMap tileMap = gameEngine.tileMap;
        Long lValueOf = null;
        boolean z = false;
        float fComputeRenderScale = computeRenderScale();
        boolean z2 = false;
        String resetReason = "";
        float f2 = fComputeRenderScale / this.renderScale;
        if (Utility.abs(f2 - 1.0f) < 0.01f) {
            f2 = 1.0f;
        }
        if (fComputeRenderScale > 0.6d) {
            float f3 = 0.3f;
            if (GameEngine.isPC()) {
                f3 = 0.1f;
            }
            if (fComputeRenderScale - this.renderScale > f3) {
                z2 = true;
                resetReason = "zoom-enlarge";
            }
            if (fComputeRenderScale == 1.0f && this.renderScale != 1.0f) {
                z2 = true;
                resetReason = "zoom-unit-scale";
            }
        }
        if (f2 != 1.0f) {
            int i = 10;
            float f4 = 0.03f;
            if (fComputeRenderScale < 0.3f) {
                i = 20;
                f4 = 0.09f;
            } else if (fComputeRenderScale < 0.5f) {
                i = 20;
                f4 = 0.07f;
            }
            if (fComputeRenderScale > 1.3f) {
                i = 7;
            }
            if (!GameEngine.isPC()) {
                i += 10;
            }
            boolean referenceChanged = Utility.abs(tileMap.fogScale - fComputeRenderScale) > 0.03f;
            if (referenceChanged) {
                tileMap.fogScale = gameEngine.zoom;
                tileMap.fogFadeStep = 0;
            } else {
                tileMap.fogFadeStep++;
            }
            if (timeBasedZoomCacheEnabled) {
                // This controls only terrain-cache resolution. The supplied f is simulation time;
                // monotonic real time keeps the display cadence independent of owner refresh rate.
                if (zoomCacheCadence.shouldRefresh(System.nanoTime(), !referenceChanged,
                        Utility.abs(fComputeRenderScale - this.renderScale) > f4, i)) {
                    tileMap.fogFadeSpeed = 0.0f;
                    if (!z2) resetReason = "zoom-time";
                    z2 = true;
                }
            } else {
                if (tileMap.fogFadeStep < 3) {
                    tileMap.fogFadeSpeed = 0.0f;
                } else if (Utility.abs(fComputeRenderScale - this.renderScale) > f4) {
                    tileMap.fogFadeSpeed += 1.0f;
                }
                if (tileMap.fogFadeSpeed > i) {
                    tileMap.fogFadeSpeed = 0.0f;
                    if (!z2) resetReason = "zoom-frames";
                    z2 = true;
                }
            }
        } else if (timeBasedZoomCacheEnabled) {
            zoomCacheCadence.reset();
        }
        int preloadWorldMargin = Math.max(
                0,
                resourceBackend().backendCapabilities().getLayerBufferScrollPreloadWorldMargin()
        );
        int preloadX = boundedScrollPreloadMargin(preloadWorldMargin, this.gridCellsPerAxis, this.cellWorldStepSize, gameEngine.visibleWorldWidth);
        int preloadY = boundedScrollPreloadMargin(preloadWorldMargin, this.gridCellsPerAxis, this.cellWorldStepSize, gameEngine.visibleWorldHeight);
        if (!z2 && !scrollGridToViewport(gameEngine.viewpointXInt, gameEngine.viewpointYInt,
                gameEngine.visibleWorldWidth, gameEngine.visibleWorldHeight, preloadX, preloadY)) {
            z2 = true;
            resetReason = "pan-outside-grid";
        }
        if (z2) {
            updateGridParams(resetReason);
        }
        float f5 = gameEngine.zoom / this.renderScale;
        if (Utility.abs(f5 - 1.0f) < 1.0E-4f) {
            f5 = 1.0f;
        }
        float f6 = (gameEngine.currentScreenWidthPixels / f5) + 2.0f;
        float f7 = (gameEngine.currentScreenHeightPixels / f5) + 2.0f;
        if (f5 != 1.0f) {
            gameEngine.renderGraphicsEngine.k();
            gameEngine.renderGraphicsEngine.a(f5, f5);
            tileMap.tempRectTile.a(gameEngine.screenClipRect);
            tileMap.tempRectTile.c = ((int) (tileMap.tempRectTile.a + (tileMap.tempRectTile.b() / f5))) + 2;
            tileMap.tempRectTile.d = ((int) (tileMap.tempRectTile.b + (tileMap.tempRectTile.c() / f5))) + 2;
            gameEngine.renderGraphicsEngine.a(tileMap.tempRectTile);
        }
        float f8 = (this.gridOriginWorldX - gameEngine.viewpointXSnapped) * this.renderScale;
        float f9 = (this.gridOriginWorldY - gameEngine.viewpointYSnapped) * this.renderScale;
        float f10 = (int) f8;
        float f11 = (int) f9;
        int i2 = 0;
        boolean z3 = false;
        if (GameEngine.isPC() && gameEngine.zoom < 0.3d) {
            z3 = true;
        }
        this.copyBlitPaint.a(z3);
        this.copyBlitPaint.d(z3);
        this.copyBlitPaint.b(z3);
        boolean z4 = false;
        for (int i3 = 0; i3 < this.gridCellsPerAxis; i3++) {
            try {
                for (int i4 = 0; i4 < this.gridCellsPerAxis; i4++) {
                    LayerBufferCell layerBufferCell = this.gridCells[i3][i4];
                    int i5 = (int) (f10 + (i3 * this.cellWorldStepSize * this.renderScale));
                    int i6 = (int) (f11 + (i4 * this.cellWorldStepSize * this.renderScale));
                    if (layerBufferCell.enableSmoothFade && !this.useFogBlitComposite) {
                        layerBufferCell.fadeFrameCount++;
                    }
                    layerBufferCell.screenDstRect.a(i5 + 1, i6 + 1, (i5 + this.cellBufferPixelSize) - 2, (i6 + this.cellBufferPixelSize) - 2);
                    if (layerBufferCell.screenDstRect.a <= f6 && layerBufferCell.screenDstRect.b <= f7
                            && layerBufferCell.screenDstRect.c >= 0 && layerBufferCell.screenDstRect.d >= 0) {
                        if (layerBufferCell.screenDstRect.c > f6) {
                            layerBufferCell.screenDstRect.c = (int) f6;
                        }
                        if (layerBufferCell.screenDstRect.d > f7) {
                            layerBufferCell.screenDstRect.d = (int) f7;
                        }
                        int i7 = (int) ((0.0f - gameEngine.viewpointXSnapped) * this.renderScale);
                        int i8 = (int) ((0.0f - gameEngine.viewpointYSnapped) * this.renderScale);
                        int worldWidth = (int) ((tileMap.getWorldWidth() - gameEngine.viewpointXSnapped) * this.renderScale);
                        int worldHeight = (int) ((tileMap.getWorldHeight() - gameEngine.viewpointYSnapped) * this.renderScale);
                        if (layerBufferCell.screenDstRect.a < i7) {
                            layerBufferCell.screenDstRect.a = i7;
                        }
                        if (layerBufferCell.screenDstRect.b < i8) {
                            layerBufferCell.screenDstRect.b = i8;
                        }
                        if (layerBufferCell.screenDstRect.c > worldWidth) {
                            layerBufferCell.screenDstRect.c = worldWidth;
                        }
                        if (layerBufferCell.screenDstRect.d > worldHeight) {
                            layerBufferCell.screenDstRect.d = worldHeight;
                        }
                        if (!layerBufferCell.screenDstRect.a()) {
                            boolean z5 = false;
                            boolean z6 = true;
                            if (layerBufferCell.needsRedraw) {
                                z5 = true;
                                z6 = false;
                            }
                            if (layerBufferCell.enableSmoothFade) {
                                int i9 = 10;
                                if (i2 > 3) {
                                    i9 = 10 + 2;
                                }
                                if (i2 > 6) {
                                    i9 += 2;
                                }
                                if (layerBufferCell.fadeFrameCount > i9) {
                                    layerBufferCell.fadeFrameCount = 0;
                                    z5 = true;
                                    i2++;
                                }
                            }
                            if (z5) {
                                z = true;
                                boolean z7 = false;
                                long jA = PerformanceProfiler.a();
                                if (lValueOf == null) {
                                    lValueOf = Long.valueOf(jA);
                                } else {
                                    int i10 = 200;
                                    if (this.useFogBlitComposite) {
                                        i10 = 30;
                                    }
                                    if (PerformanceProfiler.a(lValueOf.longValue(), jA) > i10) {
                                        z7 = true;
                                        this.useFogBlitComposite = true;
                                    }
                                }
                                if (z7
                                        && resourceBackend().backendCapabilities().getSupportsLayerBufferPreRendering()
                                        && layerBufferCell.needsRedraw
                                        && !layerBufferCell.preRendered) {
                                    preRenderCell(i3, i4);
                                }
                                if (!z7) {
                                    if (this.smoothFogFadingEnabled) {
                                        if (layerBufferCell.fadeOutTexture != null && layerBufferCell.fadeOutTexture.p != layerBufferCell.cellLayerTexture.p) {
                                            GameEngine.log("wrong sized fadeOutBitmap width:" + layerBufferCell.fadeOutTexture.p + " vs " + layerBufferCell.cellLayerTexture.p);
                                            layerBufferCell.fadeOutTexture.o();
                                            layerBufferCell.fadeOutTexture = null;
                                        }
                                        if (layerBufferCell.fadeOutTexture == null) {
                                            try {
                                                layerBufferCell.initFadeBufferTexture();
                                            } catch (OutOfMemoryError e) {
                                                e.printStackTrace();
                                                GameEngine.reportOOM(AssetType.gameImageCreate, e);
                                                disableSmoothFogFading();
                                                GameEngine.logColored("Not enough free memory to keep smooth fog fading");
                                                System.gc();
                                            }
                                            if (this.smoothFogFadingEnabled && layerBufferCell.fadeOutTexture == null) {
                                                gameEngine.alert("Disabling smooth fog fading due to error");
                                                disableSmoothFogFading();
                                                GameEngine.logColored("fadeOutBitmap == null");
                                                System.gc();
                                            }
                                        }
                                    }
                                    if (this.smoothFogFadingEnabled) {
                                        if (layerBufferCell.fadeProgressRatio > 0.0f) {
                                        }
                                        Texture texture = layerBufferCell.cellLayerTexture;
                                        layerBufferCell.cellLayerTexture = layerBufferCell.fadeOutTexture;
                                        layerBufferCell.fadeOutTexture = texture;
                                        GraphicsEngine graphicsEngine = layerBufferCell.cellGraphicsCopy;
                                        layerBufferCell.cellGraphicsCopy = layerBufferCell.fadeOutGraphics;
                                        layerBufferCell.fadeOutGraphics = graphicsEngine;
                                        if (z6) {
                                            layerBufferCell.fadeProgressRatio = 1.0f;
                                        } else {
                                            layerBufferCell.fadeProgressRatio = 0.0f;
                                        }
                                    } else {
                                        layerBufferCell.fadeProgressRatio = 0.0f;
                                    }
                                    if (resourceBackend().backendCapabilities().getRequiresFogAtlasLock()
                                            && !z4) {
                                        TileMap.acquireFogAtlasLock();
                                        z4 = true;
                                    }
                                    gameEngine.renderGraphicsEngine.i();
                                    renderCell(i3, i4);
                                    gameEngine.renderGraphicsEngine.j();
                                    if (TileMap.fogProfilingEnabled) {
                                        PerformanceProfiler.a("re-drawTile", jA);
                                    }
                                }
                            }
                            layerBufferCell.tileSrcRect.a(layerBufferCell.screenDstRect);
                            layerBufferCell.tileSrcRect.a(-i5, -i6);
                            layerBufferCell.screenDstRectF.a(layerBufferCell.screenDstRect);
                            layerBufferCell.screenDstRectF.a(-f10, -f11);
                            layerBufferCell.screenDstRectF.a(f8, f9);
                            if (layerBufferCell.fadeProgressRatio > 0.0f) {
                                layerBufferCell.fadeBlendPaint.a(z3);
                                layerBufferCell.fadeBlendPaint.c((int) ((1.0f - layerBufferCell.fadeProgressRatio) * 255.0f));
                                gameEngine.renderGraphicsEngine.a(layerBufferCell.fadeOutTexture, layerBufferCell.tileSrcRect, layerBufferCell.screenDstRectF, this.copyBlitPaint);
                                if (layerBufferCell.fadeProgressRatio < 0.98d) {
                                    gameEngine.renderGraphicsEngine.a(layerBufferCell.cellLayerTexture, layerBufferCell.tileSrcRect, layerBufferCell.screenDstRectF, layerBufferCell.fadeBlendPaint);
                                }
                                layerBufferCell.fadeProgressRatio -= 0.1f * f;
                            } else if (layerBufferCell.cellLayerTexture.A()) {
                                gameEngine.renderGraphicsEngine.a(layerBufferCell.cellLayerTexture, layerBufferCell.screenDstRectF, this.copyBlitPaint, 0.0f, 0.0f, 0, 0);
                            } else {
                                gameEngine.renderGraphicsEngine.a(layerBufferCell.cellLayerTexture, layerBufferCell.tileSrcRect, layerBufferCell.screenDstRectF, this.copyBlitPaint);
                            }
                        }
                    } else {
                        // A cached cell wholly left or above the viewport is not visible either.
                        layerBufferCell.screenDstRect.a(0, 0, 0, 0);
                    }
                }
            } finally {
                if (z4) {
                    TileMap.releaseFogAtlasLock();
                    z4 = false;
                }
            }
        }
        if (f5 != 1.0f) {
            gameEngine.renderGraphicsEngine.l();
        }
        if (!z) {
            this.useFogBlitComposite = false;
        }
    }

    /** Display-only clock, with the legacy stability / refresh counts expressed at 60 Hz. */
    static final class ZoomCacheCadence {
        static final long STABLE_NANOS = 50_000_000L;
        private boolean sampled;
        private long previousNanos;
        private long stableNanos;
        private long refreshNanos;

        void reset() {
            sampled = false;
            stableNanos = 0;
            refreshNanos = 0;
        }

        boolean shouldRefresh(long now, boolean referenceStable, boolean differenceQualifies, int refreshFrames) {
            long elapsed = sampled ? now - previousNanos : 0;
            previousNanos = now;
            sampled = true;
            if (elapsed < 0 || !referenceStable) {
                stableNanos = 0;
                refreshNanos = 0;
                return false;
            }
            long stablePart = Math.min(elapsed, STABLE_NANOS - stableNanos);
            stableNanos += stablePart;
            if (stableNanos < STABLE_NANOS) return false;
            if (differenceQualifies) {
                long refreshPart = elapsed - stablePart;
                refreshNanos = refreshPart > Long.MAX_VALUE - refreshNanos
                        ? Long.MAX_VALUE : refreshNanos + refreshPart;
            }
            long threshold = (refreshFrames * 1_000_000_000L + 59L) / 60L;
            if (refreshNanos <= threshold) return false;
            reset();
            return true;
        }
    }
}
