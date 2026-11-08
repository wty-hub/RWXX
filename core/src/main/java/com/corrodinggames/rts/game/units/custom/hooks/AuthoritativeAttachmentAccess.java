package com.corrodinggames.rts.game.units.custom.hooks;

import com.corrodinggames.rts.game.units.custom.UnitSpawner;

/** Read-only access to the compiled attachment creation edge, without creating an attachment. */
public final class AuthoritativeAttachmentAccess {
    private AuthoritativeAttachmentAccess() { }
    public static UnitSpawner onCreate(AttachmentSlotDefinition slot) { return slot.w; }
}
