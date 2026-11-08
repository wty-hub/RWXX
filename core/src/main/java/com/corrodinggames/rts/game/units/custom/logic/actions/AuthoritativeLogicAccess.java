package com.corrodinggames.rts.game.units.custom.logic.actions;

import com.corrodinggames.rts.game.units.custom.CustomUnitActionHandler;

/** Read-only access to the compiled cooldown action dependency; no action or condition evaluation. */
public final class AuthoritativeLogicAccess {
    private AuthoritativeLogicAccess() { }
    public static CustomUnitActionHandler cooldownActions(UnitStatsAction action) {
        return action.addActionCooldownApplyToActions;
    }
}
