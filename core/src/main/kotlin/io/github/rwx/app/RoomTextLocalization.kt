package io.github.rwx.app

import io.github.rwx.i18n.I18n

/** Localize the legacy engine's fixed display text without changing discovery or chat data. */
internal fun localizeLegacyRoomText(text: String): String =
    text.lineSequence().map(::localizeLegacyRoomLine).joinToString("\n")

internal fun localizedMapTypeLabel(label: String): String = when (label) {
    "Skirmish Map", "Skirmish" -> I18n.battleroom.mapType.skirmish()
    "Custom Map" -> I18n.battleroom.mapType.custom()
    "Saved Game" -> I18n.battleroom.mapType.saved()
    "Sandbox" -> I18n.battleroom.sandbox()
    "Multiplayer" -> I18n.mainmenu.multiplayer()
    else -> label
}

internal fun localizeLegacyRoomLine(rawLine: String): String {
    val line = rawLine.trim()
    when (line) {
        "Unable to get a public IP address, check your internet connection" -> return I18n.battleroom.detail.publicIpFailed()
        "Your public address is <Open> to the internet" -> return I18n.battleroom.detail.publicOpen()
        "Your public address is <CLOSED> to the internet" -> return I18n.battleroom.detail.publicClosed()
        "Retrieving your public IP..." -> return I18n.battleroom.detail.publicIpLoading()
        "You do not have a network connection" -> return I18n.battleroom.detail.noNetwork()
        "SandBox Mode!" -> return I18n.battleroom.detail.sandbox()
        "Place any unit, Control all teams, Special powers" -> return I18n.battleroom.detail.sandboxHint()
        "Local skirmish" -> return I18n.battleroom.detail.localSkirmish()
        "No nukes" -> return I18n.battleroom.noNukes()
        "Shared control: On" -> return I18n.battleroom.detail.sharedControl()
        "Password Protection: On" -> return I18n.battleroom.detail.passwordProtected()
        "Server Visibility: Hidden" -> return I18n.battleroom.detail.serverHidden()
        "-- Required Mods: --" -> return I18n.battleroom.detail.requiredModsHeader()
        "Password Required" -> return I18n.multiplayer.roomInfo.passwordRequired()
        "Port: not open (Connecting over the internet may fail)" -> return I18n.multiplayer.roomInfo.portClosed()
        "WebRTC DataChannel: available" -> return I18n.multiplayer.roomInfo.dataChannel()
        "ICE Servers:" -> return I18n.multiplayer.roomInfo.iceServers()
        "Direct fallback: available" -> return I18n.multiplayer.roomInfo.directFallback()
    }
    fun value(prefix: String): String = line.removePrefix(prefix).trim()
    return when {
        line.startsWith("Local IP address: ") && line.contains(" port: ") -> {
            val address = value("Local IP address: ").substringBefore(" port: ")
            I18n.battleroom.detail.localIp(address, line.substringAfter(" port: "))
        }
        line.startsWith("Starting Credits: ") -> I18n.battleroom.detail.startingCredits(
            value("Starting Credits: ").let { if (it == "Default ($4000)") I18n.battleroom.startingCredits.defaultValue() else it },
        )
        line.startsWith("Starting Units: ") -> I18n.battleroom.detail.startingUnits(
            localizedStartingUnitsText(value("Starting Units: ")),
        )
        line.startsWith("Fog: ") -> I18n.battleroom.detail.fog(when (val fog = value("Fog: ")) {
            "No fog" -> I18n.battleroom.fog.none()
            "Basic", "Basic fog" -> I18n.battleroom.fog.basic()
            "Line of Sight" -> I18n.battleroom.fog.lineOfSight()
            "Unknown" -> I18n.multiplayer.unknown()
            else -> fog
        })
        line.matches(Regex("[0-9.]+X income")) -> I18n.battleroom.detail.income(line.removeSuffix("X income"))
        line.matches(Regex("[0-9]+ more mods\\.\\.\\.")) -> I18n.battleroom.detail.moreMods(line.substringBefore(" "))
        line.startsWith("mod: ") -> I18n.battleroom.detail.mod(value("mod: "))
        line.startsWith("User: ") -> I18n.multiplayer.roomInfo.host(value("User: "))
        line.startsWith("Host: ") -> I18n.multiplayer.roomInfo.host(value("Host: "))
        line.startsWith("Map: ") -> I18n.multiplayer.roomInfo.map(value("Map: "))
        line.startsWith("Url: ") -> I18n.multiplayer.roomInfo.url(value("Url: "))
        line.startsWith("Lan: ") -> I18n.multiplayer.roomInfo.lan(value("Lan: "))
        line.startsWith("Version: ") -> I18n.multiplayer.roomInfo.version(
            value("Version: ").let {
                when {
                    it == "ANY" -> I18n.multiplayer.anyVersion()
                    it.endsWith(" (different game version!)") -> I18n.multiplayer.roomInfo.versionMismatch(
                        it.removeSuffix(" (different game version!)"),
                    )
                    else -> it
                }
            },
        )
        line.startsWith("Mods Needed: ") -> I18n.multiplayer.roomInfo.modsNeeded(value("Mods Needed: "))
        line.startsWith("Transport: ") -> I18n.multiplayer.roomInfo.transport(
            when (val transport = value("Transport: ")) {
                "LAN" -> I18n.multiplayer.transport.lan()
                "Dedicated" -> I18n.multiplayer.transport.dedicated()
                "Internet" -> I18n.multiplayer.transport.internet()
                "Relay" -> I18n.multiplayer.transport.relay()
                else -> transport
            },
        )
        line.startsWith("RWXX Features: ") -> I18n.multiplayer.roomInfo.features(value("RWXX Features: "))
        line.startsWith("Discovery: ") -> I18n.multiplayer.roomInfo.discovery(value("Discovery: "))
        else -> rawLine
    }
}

private fun localizedStartingUnitsText(value: String): String = when (value) {
    "Normal (1 builder)" -> I18n.battleroom.startingUnits.normal()
    "Small Army" -> I18n.battleroom.startingUnits.smallArmy()
    "3 Engineers" -> I18n.battleroom.startingUnits.engineers()
    "3 Engineers (No Command Center)" -> I18n.battleroom.startingUnits.engineersNoBase()
    "Experimental Spider" -> I18n.battleroom.startingUnits.spider()
    "Custom" -> I18n.battleroom.startingUnits.custom()
    "Unknown" -> I18n.multiplayer.unknown()
    else -> value
}
