package com.corrodinggames.rts.game.units.custom;

import com.corrodinggames.rts.game.units.UnitContainer;
import com.corrodinggames.rts.game.units.UnitType;
import java.util.List;

/** Read-only access to already linked content. Never resolves names, logs, or executes actions. */
public final class AuthoritativeContentAccess {
    private AuthoritativeContentAccess() { }

    /** A live, read-only sequence, including the identity of its actual backing collection. */
    public static final class Sequence {
        private final Object source;
        private Sequence(Object source) { this.source = source; }
        public Object identity() { return source; }
        public int size() { return source instanceof List ? ((List<?>) source).size() : ((Object[]) source).length; }
        public Object get(int index) { return source instanceof List ? ((List<?>) source).get(index) : ((Object[]) source)[index]; }
    }
    public static Sequence sequence(List<?> list) { return list == null ? null : new Sequence(list); }
    public static Sequence sequence(Object[] array) { return array == null ? null : new Sequence(array); }
    public static Sequence references(CustomUnitConfig config) { return sequence(config.unitTypeReferences); }
    public static Sequence definitions(CustomUnitConfig config) { return sequence(config.customActionDefs); }
    public static Sequence handlers(CustomUnitConfig config) { return sequence(config.actionHandlers); }
    public static Sequence actionLists(CustomUnitConfig config) { return sequence(config.specialActionLists); }
    public static Sequence actions(UnitContainer container) { return sequence(container.a); }
    public static Sequence projectiles(CustomUnitConfig config) { return sequence(config.projectileTemplates); }
    public static Sequence conditions(CustomUnitConfig config) { return sequence(config.autoTriggerConditions); }
    public static Sequence frameConditions(CustomUnitConfig config) { return sequence(config.autoTriggerConditionsEveryFrame); }
    public static Sequence fourFrameConditions(CustomUnitConfig config) { return sequence(config.autoTriggerConditionsEvery4Frames); }
    public static Sequence eightFrameConditions(CustomUnitConfig config) { return sequence(config.autoTriggerConditionsEvery8Frames); }
    public static Sequence events(CustomUnitConfig config) { return sequence(config.eventBindings); }
    /** null means unlinked; intentionally does not call the logging handler.a(). */
    public static Sequence linkedActions(CustomUnitActionHandler handler) { return sequence(handler.actions); }
    public static Sequence spawns(UnitSpawner spawner) { return sequence(spawner.spawnQueue); }
    public static Sequence projectileSpawns(UnitSpawnList list) { return sequence(list.a); }
    public static UnitType resolvedType(UnitTypeReference reference) { return reference.unitType; }
    public static CustomProjectileTemplate resolvedProjectile(CustomUnitProjectileReference reference) { return reference.projectile; }
    public static UnitTypeReference spawnType(SpawnConfig spawn) { return spawn.unitType; }
    public static CustomUnitProjectileReference projectileType(UnitSpawnEntry spawn) { return spawn.a; }
    public static Object[] conditionEdges(CustomUnitCondition condition) { return new Object[] {condition.logicBoolean, condition.action}; }
    public static Object[] eventEdges(CustomEventBinding event) { return new Object[] {event.a, event.b, event.c, event.d}; }
    public static Object[] spawnEdges(SpawnConfig spawn) {
        return new Object[] {spawn.unitType, spawn.spawnSource, spawn.copyWaypointsFrom, spawn.addResources};
    }
}
