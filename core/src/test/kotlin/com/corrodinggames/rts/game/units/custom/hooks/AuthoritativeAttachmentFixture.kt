package com.corrodinggames.rts.game.units.custom.hooks

import com.corrodinggames.rts.game.units.custom.UnitSpawner

/** Test-only package access; no setter is added to the production read-only bridge. */
internal fun AttachmentSlotDefinition.setFixtureCreation(spawner: UnitSpawner?) { w = spawner }
internal fun AttachmentSlotDefinition.setFixtureIdentity(index: Short, name: String) { a = index; b = name }
