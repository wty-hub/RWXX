package io.github.rwx.i18n

import java.util.Locale

// Auto-generated file - Do not modify
object I18n {
    const val defaultLocaleTag: String = "en"

    @Volatile
    var currentLocale: Locale = Locale.getDefault()
        private set

    val supportedLocaleTags: Set<String> = linkedSetOf(
        "en",
        "zh-CN",
    )

    val supportedLocales: List<Locale> = supportedLocaleTags.map(Locale::forLanguageTag)

    fun setLocale(locale: Locale) {
        currentLocale = locale
    }

    private fun entry(
        key: String,
        translations: Map<String, String>,
    ): I18nText = I18nText(
        key = key,
        translations = translations,
        localeProvider = { currentLocale },
        fallbackLocaleTag = defaultLocaleTag,
    )

    object battleroom {
        val addAI = entry(
            key = "battleroom.addAI",
            translations = linkedMapOf(
                "en" to "Add AI",
                "zh-CN" to "添加AI",
            ),
        )

        object aiDifficulty {
            val easy = entry(
                key = "battleroom.aiDifficulty.easy",
                translations = linkedMapOf(
                    "en" to "Easy",
                    "zh-CN" to "简单",
                ),
            )

            val hard = entry(
                key = "battleroom.aiDifficulty.hard",
                translations = linkedMapOf(
                    "en" to "Hard",
                    "zh-CN" to "困难",
                ),
            )

            val impossible = entry(
                key = "battleroom.aiDifficulty.impossible",
                translations = linkedMapOf(
                    "en" to "Impossible",
                    "zh-CN" to "不可能",
                ),
            )

            val label = entry(
                key = "battleroom.aiDifficulty.label",
                translations = linkedMapOf(
                    "en" to "AI difficulty",
                    "zh-CN" to "电脑难度",
                ),
            )

            val medium = entry(
                key = "battleroom.aiDifficulty.medium",
                translations = linkedMapOf(
                    "en" to "Medium",
                    "zh-CN" to "中等",
                ),
            )

            val overrideLabel = entry(
                key = "battleroom.aiDifficulty.overrideLabel",
                translations = linkedMapOf(
                    "en" to "AI difficulty override",
                    "zh-CN" to "单独设置电脑难度",
                ),
            )

            val veryEasy = entry(
                key = "battleroom.aiDifficulty.veryEasy",
                translations = linkedMapOf(
                    "en" to "Very Easy",
                    "zh-CN" to "非常简单",
                ),
            )

            val veryHard = entry(
                key = "battleroom.aiDifficulty.veryHard",
                translations = linkedMapOf(
                    "en" to "Very Hard",
                    "zh-CN" to "非常困难",
                ),
            )

        }

        val allowSpectators = entry(
            key = "battleroom.allowSpectators",
            translations = linkedMapOf(
                "en" to "Allow spectators",
                "zh-CN" to "允许旁观",
            ),
        )

        val apply = entry(
            key = "battleroom.apply",
            translations = linkedMapOf(
                "en" to "Apply",
                "zh-CN" to "应用",
            ),
        )

        val auto = entry(
            key = "battleroom.auto",
            translations = linkedMapOf(
                "en" to "Auto",
                "zh-CN" to "自动",
            ),
        )

        val changeTeam = entry(
            key = "battleroom.changeTeam",
            translations = linkedMapOf(
                "en" to "Change Team",
                "zh-CN" to "更改队伍",
            ),
        )

        val chat = entry(
            key = "battleroom.chat",
            translations = linkedMapOf(
                "en" to "Chat",
                "zh-CN" to "聊天",
            ),
        )

        val close = entry(
            key = "battleroom.close",
            translations = linkedMapOf(
                "en" to "Close",
                "zh-CN" to "关闭",
            ),
        )

        val defaultValue = entry(
            key = "battleroom.defaultValue",
            translations = linkedMapOf(
                "en" to "Default",
                "zh-CN" to "默认",
            ),
        )

        object detail {
            val fog = entry(
                key = "battleroom.detail.fog",
                translations = linkedMapOf(
                    "en" to "Fog: {0}",
                    "zh-CN" to "战争迷雾：{0}",
                ),
            )

            val income = entry(
                key = "battleroom.detail.income",
                translations = linkedMapOf(
                    "en" to "{0}X income",
                    "zh-CN" to "收入倍率：{0} 倍",
                ),
            )

            val localIp = entry(
                key = "battleroom.detail.localIp",
                translations = linkedMapOf(
                    "en" to "Local IP address: {0} port: {1}",
                    "zh-CN" to "局域网地址：{0}，端口：{1}",
                ),
            )

            val localSkirmish = entry(
                key = "battleroom.detail.localSkirmish",
                translations = linkedMapOf(
                    "en" to "Local skirmish",
                    "zh-CN" to "本地遭遇战",
                ),
            )

            val mod = entry(
                key = "battleroom.detail.mod",
                translations = linkedMapOf(
                    "en" to "mod: {0}",
                    "zh-CN" to "模组：{0}",
                ),
            )

            val moreMods = entry(
                key = "battleroom.detail.moreMods",
                translations = linkedMapOf(
                    "en" to "{0} more mods...",
                    "zh-CN" to "另有 {0} 个模组…",
                ),
            )

            val noNetwork = entry(
                key = "battleroom.detail.noNetwork",
                translations = linkedMapOf(
                    "en" to "You do not have a network connection",
                    "zh-CN" to "当前没有网络连接",
                ),
            )

            val passwordProtected = entry(
                key = "battleroom.detail.passwordProtected",
                translations = linkedMapOf(
                    "en" to "Password Protection: On",
                    "zh-CN" to "密码保护：开启",
                ),
            )

            val publicClosed = entry(
                key = "battleroom.detail.publicClosed",
                translations = linkedMapOf(
                    "en" to "Your public address is <CLOSED> to the internet",
                    "zh-CN" to "公网端口未开放",
                ),
            )

            val publicIpFailed = entry(
                key = "battleroom.detail.publicIpFailed",
                translations = linkedMapOf(
                    "en" to "Unable to get a public IP address, check your internet connection",
                    "zh-CN" to "无法获取公网地址，请检查网络连接",
                ),
            )

            val publicIpLoading = entry(
                key = "battleroom.detail.publicIpLoading",
                translations = linkedMapOf(
                    "en" to "Retrieving your public IP...",
                    "zh-CN" to "正在获取公网地址…",
                ),
            )

            val publicOpen = entry(
                key = "battleroom.detail.publicOpen",
                translations = linkedMapOf(
                    "en" to "Your public address is <Open> to the internet",
                    "zh-CN" to "公网端口已开放",
                ),
            )

            val requiredMods = entry(
                key = "battleroom.detail.requiredMods",
                translations = linkedMapOf(
                    "en" to "Required mods: {0}",
                    "zh-CN" to "所需模组：{0}",
                ),
            )

            val requiredModsHeader = entry(
                key = "battleroom.detail.requiredModsHeader",
                translations = linkedMapOf(
                    "en" to "-- Required Mods: --",
                    "zh-CN" to "-- 所需模组 --",
                ),
            )

            val roomLocked = entry(
                key = "battleroom.detail.roomLocked",
                translations = linkedMapOf(
                    "en" to "Room locked",
                    "zh-CN" to "房间已锁定",
                ),
            )

            val sandbox = entry(
                key = "battleroom.detail.sandbox",
                translations = linkedMapOf(
                    "en" to "SandBox Mode!",
                    "zh-CN" to "沙盒模式",
                ),
            )

            val sandboxHint = entry(
                key = "battleroom.detail.sandboxHint",
                translations = linkedMapOf(
                    "en" to "Place any unit, Control all teams, Special powers",
                    "zh-CN" to "放置任意单位，控制所有队伍，使用特殊能力",
                ),
            )

            val serverHidden = entry(
                key = "battleroom.detail.serverHidden",
                translations = linkedMapOf(
                    "en" to "Server Visibility: Hidden",
                    "zh-CN" to "服务器可见性：隐藏",
                ),
            )

            val sharedControl = entry(
                key = "battleroom.detail.sharedControl",
                translations = linkedMapOf(
                    "en" to "Shared control: On",
                    "zh-CN" to "共享控制权：开启",
                ),
            )

            val startingCredits = entry(
                key = "battleroom.detail.startingCredits",
                translations = linkedMapOf(
                    "en" to "Starting Credits: {0}",
                    "zh-CN" to "初始资金：{0}",
                ),
            )

            val startingUnits = entry(
                key = "battleroom.detail.startingUnits",
                translations = linkedMapOf(
                    "en" to "Starting Units: {0}",
                    "zh-CN" to "初始单位：{0}",
                ),
            )

        }

        val emptyChat = entry(
            key = "battleroom.emptyChat",
            translations = linkedMapOf(
                "en" to "No messages yet",
                "zh-CN" to "暂无消息",
            ),
        )

        object failure {
            val addAi = entry(
                key = "battleroom.failure.addAi",
                translations = linkedMapOf(
                    "en" to "Unable to add AI",
                    "zh-CN" to "无法添加电脑玩家",
                ),
            )

            val chat = entry(
                key = "battleroom.failure.chat",
                translations = linkedMapOf(
                    "en" to "Unable to send chat",
                    "zh-CN" to "无法发送聊天消息",
                ),
            )

            val kick = entry(
                key = "battleroom.failure.kick",
                translations = linkedMapOf(
                    "en" to "Unable to kick player",
                    "zh-CN" to "无法踢出玩家",
                ),
            )

            val options = entry(
                key = "battleroom.failure.options",
                translations = linkedMapOf(
                    "en" to "Unable to apply options",
                    "zh-CN" to "无法应用游戏选项",
                ),
            )

            val playerConfig = entry(
                key = "battleroom.failure.playerConfig",
                translations = linkedMapOf(
                    "en" to "Unable to apply player config",
                    "zh-CN" to "无法应用玩家设置",
                ),
            )

            val teams = entry(
                key = "battleroom.failure.teams",
                translations = linkedMapOf(
                    "en" to "Unable to set teams",
                    "zh-CN" to "无法设置队伍",
                ),
            )

        }

        val fixedAllyTeams = entry(
            key = "battleroom.fixedAllyTeams",
            translations = linkedMapOf(
                "en" to "Fixed ally teams",
                "zh-CN" to "固定盟友队伍",
            ),
        )

        object fog {
            val basic = entry(
                key = "battleroom.fog.basic",
                translations = linkedMapOf(
                    "en" to "Basic",
                    "zh-CN" to "基础迷雾",
                ),
            )

            val label = entry(
                key = "battleroom.fog.label",
                translations = linkedMapOf(
                    "en" to "Fog",
                    "zh-CN" to "战争迷雾",
                ),
            )

            val lineOfSight = entry(
                key = "battleroom.fog.lineOfSight",
                translations = linkedMapOf(
                    "en" to "Line of Sight",
                    "zh-CN" to "视线迷雾",
                ),
            )

            val none = entry(
                key = "battleroom.fog.none",
                translations = linkedMapOf(
                    "en" to "No fog",
                    "zh-CN" to "无迷雾",
                ),
            )

        }

        object heading {
            val name = entry(
                key = "battleroom.heading.name",
                translations = linkedMapOf(
                    "en" to "Name",
                    "zh-CN" to "名称",
                ),
            )

            val ping = entry(
                key = "battleroom.heading.ping",
                translations = linkedMapOf(
                    "en" to "Ping",
                    "zh-CN" to "延迟",
                ),
            )

            val spawn = entry(
                key = "battleroom.heading.spawn",
                translations = linkedMapOf(
                    "en" to "Spawn",
                    "zh-CN" to "出生点",
                ),
            )

            val team = entry(
                key = "battleroom.heading.team",
                translations = linkedMapOf(
                    "en" to "Team",
                    "zh-CN" to "队伍",
                ),
            )

        }

        val host = entry(
            key = "battleroom.host",
            translations = linkedMapOf(
                "en" to "Host",
                "zh-CN" to "房主",
            ),
        )

        object hostRequired {
            val addAi = entry(
                key = "battleroom.hostRequired.addAi",
                translations = linkedMapOf(
                    "en" to "Add AI requires a hosted RW room",
                    "zh-CN" to "请先创建房间，再添加电脑玩家",
                ),
            )

            val chat = entry(
                key = "battleroom.hostRequired.chat",
                translations = linkedMapOf(
                    "en" to "Chat requires a hosted RW room",
                    "zh-CN" to "请先进入房间，再发送聊天消息",
                ),
            )

            val options = entry(
                key = "battleroom.hostRequired.options",
                translations = linkedMapOf(
                    "en" to "Game options require a hosted RW room",
                    "zh-CN" to "请先创建房间，再设置游戏选项",
                ),
            )

            val playerConfig = entry(
                key = "battleroom.hostRequired.playerConfig",
                translations = linkedMapOf(
                    "en" to "Player config requires a hosted RW room",
                    "zh-CN" to "请先创建房间，再修改玩家设置",
                ),
            )

            val teams = entry(
                key = "battleroom.hostRequired.teams",
                translations = linkedMapOf(
                    "en" to "Set Teams requires a hosted RW room",
                    "zh-CN" to "请先创建房间，再设置队伍",
                ),
            )

        }

        val income = entry(
            key = "battleroom.income",
            translations = linkedMapOf(
                "en" to "Income",
                "zh-CN" to "收入倍率",
            ),
        )

        val kick = entry(
            key = "battleroom.kick",
            translations = linkedMapOf(
                "en" to "Kick",
                "zh-CN" to "踢出玩家",
            ),
        )

        val lockRoom = entry(
            key = "battleroom.lockRoom",
            translations = linkedMapOf(
                "en" to "Lock room (no new players)",
                "zh-CN" to "锁定房间（禁止新玩家加入）",
            ),
        )

        object mapType {
            val custom = entry(
                key = "battleroom.mapType.custom",
                translations = linkedMapOf(
                    "en" to "Custom Map",
                    "zh-CN" to "自定义地图",
                ),
            )

            val saved = entry(
                key = "battleroom.mapType.saved",
                translations = linkedMapOf(
                    "en" to "Saved Game",
                    "zh-CN" to "存档",
                ),
            )

            val skirmish = entry(
                key = "battleroom.mapType.skirmish",
                translations = linkedMapOf(
                    "en" to "Skirmish Map",
                    "zh-CN" to "遭遇战地图",
                ),
            )

        }

        val maxPlayers = entry(
            key = "battleroom.maxPlayers",
            translations = linkedMapOf(
                "en" to "Max players",
                "zh-CN" to "最大玩家数",
            ),
        )

        val noMapSelected = entry(
            key = "battleroom.noMapSelected",
            translations = linkedMapOf(
                "en" to "No map selected",
                "zh-CN" to "未选择地图",
            ),
        )

        val noNukes = entry(
            key = "battleroom.noNukes",
            translations = linkedMapOf(
                "en" to "No nukes",
                "zh-CN" to "禁用核弹",
            ),
        )

        val options = entry(
            key = "battleroom.options",
            translations = linkedMapOf(
                "en" to "Game Options",
                "zh-CN" to "游戏选项",
            ),
        )

        val optionsHint = entry(
            key = "battleroom.optionsHint",
            translations = linkedMapOf(
                "en" to "Configure this battle room before starting.",
                "zh-CN" to "开始游戏前配置当前房间。",
            ),
        )

        val originalBlocked = entry(
            key = "battleroom.originalBlocked",
            translations = linkedMapOf(
                "en" to "Original multiplayer blocked",
                "zh-CN" to "无法使用原版联机",
            ),
        )

        val p2pEnabled = entry(
            key = "battleroom.p2pEnabled",
            translations = linkedMapOf(
                "en" to "RWXX P2P enabled",
                "zh-CN" to "已启用 RWXX 点对点联机",
            ),
        )

        val playerConfig = entry(
            key = "battleroom.playerConfig",
            translations = linkedMapOf(
                "en" to "Player Config",
                "zh-CN" to "玩家设置",
            ),
        )

        val playerConfigHint = entry(
            key = "battleroom.playerConfigHint",
            translations = linkedMapOf(
                "en" to "Set spawn point and ally team.",
                "zh-CN" to "设置出生点和盟友队伍。",
            ),
        )

        val players = entry(
            key = "battleroom.players",
            translations = linkedMapOf(
                "en" to "Players",
                "zh-CN" to "玩家",
            ),
        )

        val ready = entry(
            key = "battleroom.ready",
            translations = linkedMapOf(
                "en" to "Ready",
                "zh-CN" to "已准备",
            ),
        )

        object rejected {
            val addAi = entry(
                key = "battleroom.rejected.addAi",
                translations = linkedMapOf(
                    "en" to "Game session rejected add AI request",
                    "zh-CN" to "游戏未能添加电脑玩家",
                ),
            )

            val chat = entry(
                key = "battleroom.rejected.chat",
                translations = linkedMapOf(
                    "en" to "Game session rejected chat request",
                    "zh-CN" to "游戏未能发送聊天消息",
                ),
            )

            val kick = entry(
                key = "battleroom.rejected.kick",
                translations = linkedMapOf(
                    "en" to "Game session rejected kick request",
                    "zh-CN" to "游戏未能踢出玩家",
                ),
            )

            val options = entry(
                key = "battleroom.rejected.options",
                translations = linkedMapOf(
                    "en" to "Game session rejected battle room options",
                    "zh-CN" to "游戏未能应用房间选项",
                ),
            )

            val playerConfig = entry(
                key = "battleroom.rejected.playerConfig",
                translations = linkedMapOf(
                    "en" to "Game session rejected player config request",
                    "zh-CN" to "游戏未能应用玩家设置",
                ),
            )

            val teams = entry(
                key = "battleroom.rejected.teams",
                translations = linkedMapOf(
                    "en" to "Game session rejected team layout request",
                    "zh-CN" to "游戏未能应用队伍分配",
                ),
            )

        }

        val removeAi = entry(
            key = "battleroom.removeAi",
            translations = linkedMapOf(
                "en" to "Remove AI",
                "zh-CN" to "移除电脑",
            ),
        )

        val revealedMap = entry(
            key = "battleroom.revealedMap",
            translations = linkedMapOf(
                "en" to "Revealed Map",
                "zh-CN" to "显示完整地图",
            ),
        )

        val sandbox = entry(
            key = "battleroom.sandbox",
            translations = linkedMapOf(
                "en" to "Sandbox",
                "zh-CN" to "沙盒",
            ),
        )

        val selectMap = entry(
            key = "battleroom.selectMap",
            translations = linkedMapOf(
                "en" to "Select Map",
                "zh-CN" to "选择地图",
            ),
        )

        val send = entry(
            key = "battleroom.send",
            translations = linkedMapOf(
                "en" to "Send",
                "zh-CN" to "发送",
            ),
        )

        val sendMessage = entry(
            key = "battleroom.sendMessage",
            translations = linkedMapOf(
                "en" to "Send a message",
                "zh-CN" to "发送消息",
            ),
        )

        val sharedControl = entry(
            key = "battleroom.sharedControl",
            translations = linkedMapOf(
                "en" to "Shared control",
                "zh-CN" to "共享控制权",
            ),
        )

        val singlePlayer = entry(
            key = "battleroom.singlePlayer",
            translations = linkedMapOf(
                "en" to "Single-player",
                "zh-CN" to "单人游戏",
            ),
        )

        val spawnPoint = entry(
            key = "battleroom.spawnPoint",
            translations = linkedMapOf(
                "en" to "Spawn point",
                "zh-CN" to "出生点",
            ),
        )

        val spectator = entry(
            key = "battleroom.spectator",
            translations = linkedMapOf(
                "en" to "Spec",
                "zh-CN" to "旁观",
            ),
        )

        val start = entry(
            key = "battleroom.start",
            translations = linkedMapOf(
                "en" to "Start",
                "zh-CN" to "开始游戏",
            ),
        )

        object startingCredits {
            val defaultValue = entry(
                key = "battleroom.startingCredits.defaultValue",
                translations = linkedMapOf(
                    "en" to "Default (\$4000)",
                    "zh-CN" to "默认（\$4000）",
                ),
            )

            val label = entry(
                key = "battleroom.startingCredits.label",
                translations = linkedMapOf(
                    "en" to "Starting credits",
                    "zh-CN" to "初始资金",
                ),
            )

        }

        object startingUnits {
            val custom = entry(
                key = "battleroom.startingUnits.custom",
                translations = linkedMapOf(
                    "en" to "Custom",
                    "zh-CN" to "自定义",
                ),
            )

            val engineers = entry(
                key = "battleroom.startingUnits.engineers",
                translations = linkedMapOf(
                    "en" to "3 Engineers",
                    "zh-CN" to "3 名工程师",
                ),
            )

            val engineersNoBase = entry(
                key = "battleroom.startingUnits.engineersNoBase",
                translations = linkedMapOf(
                    "en" to "3 Engineers (No Command Center)",
                    "zh-CN" to "3 名工程师（无指挥中心）",
                ),
            )

            val label = entry(
                key = "battleroom.startingUnits.label",
                translations = linkedMapOf(
                    "en" to "Starting units",
                    "zh-CN" to "初始单位",
                ),
            )

            val normal = entry(
                key = "battleroom.startingUnits.normal",
                translations = linkedMapOf(
                    "en" to "Normal (1 builder)",
                    "zh-CN" to "标准（1 辆建造者）",
                ),
            )

            val overrideLabel = entry(
                key = "battleroom.startingUnits.overrideLabel",
                translations = linkedMapOf(
                    "en" to "Starting units override",
                    "zh-CN" to "单独设置初始单位",
                ),
            )

            val smallArmy = entry(
                key = "battleroom.startingUnits.smallArmy",
                translations = linkedMapOf(
                    "en" to "Small Army",
                    "zh-CN" to "小型军队",
                ),
            )

            val spider = entry(
                key = "battleroom.startingUnits.spider",
                translations = linkedMapOf(
                    "en" to "Experimental Spider",
                    "zh-CN" to "实验型蜘蛛",
                ),
            )

        }

        object teamLayout {
            val allVsAi = entry(
                key = "battleroom.teamLayout.allVsAi",
                translations = linkedMapOf(
                    "en" to "All vs AI",
                    "zh-CN" to "全体对抗电脑",
                ),
            )

            val allVsTwo = entry(
                key = "battleroom.teamLayout.allVsTwo",
                translations = linkedMapOf(
                    "en" to "All vs 2 (survival)",
                    "zh-CN" to "全体对抗两名敌人（生存）",
                ),
            )

            val ffa = entry(
                key = "battleroom.teamLayout.ffa",
                translations = linkedMapOf(
                    "en" to "FFA",
                    "zh-CN" to "自由混战",
                ),
            )

            val label = entry(
                key = "battleroom.teamLayout.label",
                translations = linkedMapOf(
                    "en" to "Team layout",
                    "zh-CN" to "队伍分配",
                ),
            )

            val noChange = entry(
                key = "battleroom.teamLayout.noChange",
                translations = linkedMapOf(
                    "en" to "No change",
                    "zh-CN" to "保持不变",
                ),
            )

            val random = entry(
                key = "battleroom.teamLayout.random",
                translations = linkedMapOf(
                    "en" to "Random Team",
                    "zh-CN" to "随机分队",
                ),
            )

            val spectators = entry(
                key = "battleroom.teamLayout.spectators",
                translations = linkedMapOf(
                    "en" to "Spectators",
                    "zh-CN" to "全部旁观",
                ),
            )

            val threeSides = entry(
                key = "battleroom.teamLayout.threeSides",
                translations = linkedMapOf(
                    "en" to "3 Sides",
                    "zh-CN" to "三方对战",
                ),
            )

            val twoSides = entry(
                key = "battleroom.teamLayout.twoSides",
                translations = linkedMapOf(
                    "en" to "2 Sides",
                    "zh-CN" to "两方对战",
                ),
            )

        }

        val teamLock = entry(
            key = "battleroom.teamLock",
            translations = linkedMapOf(
                "en" to "Team lock",
                "zh-CN" to "锁定队伍",
            ),
        )

        val title = entry(
            key = "battleroom.title",
            translations = linkedMapOf(
                "en" to "Battleroom",
                "zh-CN" to "战役室",
            ),
        )

        val unableToStartGame = entry(
            key = "battleroom.unableToStartGame",
            translations = linkedMapOf(
                "en" to "Unable to start game: {0}",
                "zh-CN" to "无法开始游戏：{0}",
            ),
        )

        val waiting = entry(
            key = "battleroom.waiting",
            translations = linkedMapOf(
                "en" to "Waiting",
                "zh-CN" to "等待中",
            ),
        )

    }

    object common {
        val actionUnavailable = entry(
            key = "common.actionUnavailable",
            translations = linkedMapOf(
                "en" to "Action unavailable",
                "zh-CN" to "操作不可用",
            ),
        )

        val back = entry(
            key = "common.back",
            translations = linkedMapOf(
                "en" to "Back",
                "zh-CN" to "返回",
            ),
        )

        val cancel = entry(
            key = "common.cancel",
            translations = linkedMapOf(
                "en" to "Cancel",
                "zh-CN" to "取消",
            ),
        )

        val close = entry(
            key = "common.close",
            translations = linkedMapOf(
                "en" to "Close",
                "zh-CN" to "关闭",
            ),
        )

        val join = entry(
            key = "common.join",
            translations = linkedMapOf(
                "en" to "Join",
                "zh-CN" to "加入",
            ),
        )

        val no = entry(
            key = "common.no",
            translations = linkedMapOf(
                "en" to "No",
                "zh-CN" to "否",
            ),
        )

        val off = entry(
            key = "common.off",
            translations = linkedMapOf(
                "en" to "Off",
                "zh-CN" to "关闭",
            ),
        )

        val ok = entry(
            key = "common.ok",
            translations = linkedMapOf(
                "en" to "OK",
                "zh-CN" to "确定",
            ),
        )

        val on = entry(
            key = "common.on",
            translations = linkedMapOf(
                "en" to "On",
                "zh-CN" to "开启",
            ),
        )

        val required = entry(
            key = "common.required",
            translations = linkedMapOf(
                "en" to "Required",
                "zh-CN" to "需要",
            ),
        )

        val save = entry(
            key = "common.save",
            translations = linkedMapOf(
                "en" to "Save",
                "zh-CN" to "保存",
            ),
        )

    }

    object ingame {
        val export = entry(
            key = "ingame.export",
            translations = linkedMapOf(
                "en" to "Export",
                "zh-CN" to "导出",
            ),
        )

        val exportMap = entry(
            key = "ingame.exportMap",
            translations = linkedMapOf(
                "en" to "Export Map",
                "zh-CN" to "导出地图",
            ),
        )

        val exportPrompt = entry(
            key = "ingame.exportPrompt",
            translations = linkedMapOf(
                "en" to "Enter a name to export the map as.",
                "zh-CN" to "请输入导出地图的名称。",
            ),
        )

        val mapName = entry(
            key = "ingame.mapName",
            translations = linkedMapOf(
                "en" to "Map name",
                "zh-CN" to "地图名称",
            ),
        )

        val noPlayers = entry(
            key = "ingame.noPlayers",
            translations = linkedMapOf(
                "en" to "No multiplayer players found.",
                "zh-CN" to "未找到联机玩家。",
            ),
        )

        val playerInfo = entry(
            key = "ingame.playerInfo",
            translations = linkedMapOf(
                "en" to "{0}  #{1}  Team {2}{3}",
                "zh-CN" to "{0}  出生点 {1}  队伍 {2}{3}",
            ),
        )

        val saveGame = entry(
            key = "ingame.saveGame",
            translations = linkedMapOf(
                "en" to "Save Game",
                "zh-CN" to "保存游戏",
            ),
        )

        val saveName = entry(
            key = "ingame.saveName",
            translations = linkedMapOf(
                "en" to "Save name",
                "zh-CN" to "存档名称",
            ),
        )

        val savePrompt = entry(
            key = "ingame.savePrompt",
            translations = linkedMapOf(
                "en" to "Enter a name to save the game under.",
                "zh-CN" to "请输入存档名称。",
            ),
        )

        val teamChat = entry(
            key = "ingame.teamChat",
            translations = linkedMapOf(
                "en" to "Team Chat",
                "zh-CN" to "队伍聊天",
            ),
        )

    }

    object interfaceChrome {
        val gameName = entry(
            key = "interfaceChrome.gameName",
            translations = linkedMapOf(
                "en" to "RUSTED WARFARE EXTENSION",
                "zh-CN" to "铁锈战争扩展",
            ),
        )

        val system = entry(
            key = "interfaceChrome.system",
            translations = linkedMapOf(
                "en" to "SYSTEM  /  01",
                "zh-CN" to "系统  /  01",
            ),
        )

        val tacticalInterface = entry(
            key = "interfaceChrome.tacticalInterface",
            translations = linkedMapOf(
                "en" to "RWXX  //  TACTICAL INTERFACE",
                "zh-CN" to "RWXX  //  战术界面",
            ),
        )

    }

    object levelselect {
        val allMaps = entry(
            key = "levelselect.allMaps",
            translations = linkedMapOf(
                "en" to "All maps",
                "zh-CN" to "全部地图",
            ),
        )

        val compatibility = entry(
            key = "levelselect.compatibility",
            translations = linkedMapOf(
                "en" to "Single-player / RWXX P2P",
                "zh-CN" to "单人 / RWXX 点对点联机",
            ),
        )

        val filter = entry(
            key = "levelselect.filter",
            translations = linkedMapOf(
                "en" to "Filter",
                "zh-CN" to "筛选",
            ),
        )

        object gameInProgress {
            val message = entry(
                key = "levelselect.gameInProgress.message",
                translations = linkedMapOf(
                    "en" to "A game is already running. Start a new game or continue the current game?",
                    "zh-CN" to "当前已有正在进行的游戏。要开始新游戏，还是继续当前游戏？",
                ),
            )

            val startNew = entry(
                key = "levelselect.gameInProgress.startNew",
                translations = linkedMapOf(
                    "en" to "Start New",
                    "zh-CN" to "开始新游戏",
                ),
            )

            val title = entry(
                key = "levelselect.gameInProgress.title",
                translations = linkedMapOf(
                    "en" to "Game in progress",
                    "zh-CN" to "游戏进行中",
                ),
            )

        }

        object linkedMaps {
            val anotherPlayer = entry(
                key = "levelselect.linkedMaps.anotherPlayer",
                translations = linkedMapOf(
                    "en" to "another player",
                    "zh-CN" to "其他玩家",
                ),
            )

            val current = entry(
                key = "levelselect.linkedMaps.current",
                translations = linkedMapOf(
                    "en" to "Current",
                    "zh-CN" to "当前地图",
                ),
            )

            val hudUnavailable = entry(
                key = "levelselect.linkedMaps.hudUnavailable",
                translations = linkedMapOf(
                    "en" to "RWXX multi-map switching is not wired to the HUD yet: {0}",
                    "zh-CN" to "游戏界面尚未支持 RWXX 多地图切换：{0}",
                ),
            )

            val missingTargets = entry(
                key = "levelselect.linkedMaps.missingTargets",
                translations = linkedMapOf(
                    "en" to "Linked map missing: {0}",
                    "zh-CN" to "缺少关联地图：{0}",
                ),
            )

            val noActiveMap = entry(
                key = "levelselect.linkedMaps.noActiveMap",
                translations = linkedMapOf(
                    "en" to "No active RWXX map.",
                    "zh-CN" to "当前没有运行中的 RWXX 地图。",
                ),
            )

            val noLinkedMaps = entry(
                key = "levelselect.linkedMaps.noLinkedMaps",
                translations = linkedMapOf(
                    "en" to "This map has no linked RWXX maps.",
                    "zh-CN" to "此地图没有关联的 RWXX 地图。",
                ),
            )

            val notFound = entry(
                key = "levelselect.linkedMaps.notFound",
                translations = linkedMapOf(
                    "en" to "Linked map not found: {0}",
                    "zh-CN" to "未找到关联地图：{0}",
                ),
            )

            val platformUnavailable = entry(
                key = "levelselect.linkedMaps.platformUnavailable",
                translations = linkedMapOf(
                    "en" to "RWXX map switching is unavailable on this platform.",
                    "zh-CN" to "当前平台暂不支持 RWXX 地图切换。",
                ),
            )

            val portalTransferSent = entry(
                key = "levelselect.linkedMaps.portalTransferSent",
                translations = linkedMapOf(
                    "en" to "RWXX portal transfer sent",
                    "zh-CN" to "已发送 RWXX 传送请求",
                ),
            )

            val select = entry(
                key = "levelselect.linkedMaps.select",
                translations = linkedMapOf(
                    "en" to "Select a linked map.",
                    "zh-CN" to "请选择要切换的关联地图。",
                ),
            )

            val simulatedBy = entry(
                key = "levelselect.linkedMaps.simulatedBy",
                translations = linkedMapOf(
                    "en" to "This map is simulated by {0}.",
                    "zh-CN" to "此地图由{0}负责模拟。",
                ),
            )

            val simulator = entry(
                key = "levelselect.linkedMaps.simulator",
                translations = linkedMapOf(
                    "en" to "Sim: {0}",
                    "zh-CN" to "模拟者：{0}",
                ),
            )

            val snapshotFailed = entry(
                key = "levelselect.linkedMaps.snapshotFailed",
                translations = linkedMapOf(
                    "en" to "Map snapshot failed",
                    "zh-CN" to "地图快照保存失败",
                ),
            )

            val title = entry(
                key = "levelselect.linkedMaps.title",
                translations = linkedMapOf(
                    "en" to "RWXX Maps",
                    "zh-CN" to "RWXX 地图",
                ),
            )

            val unavailable = entry(
                key = "levelselect.linkedMaps.unavailable",
                translations = linkedMapOf(
                    "en" to "Under development, temporarily unavailable",
                    "zh-CN" to "功能开发中，暂不可用",
                ),
            )

            val unitTransferFailed = entry(
                key = "levelselect.linkedMaps.unitTransferFailed",
                translations = linkedMapOf(
                    "en" to "Unit transfer failed",
                    "zh-CN" to "单位传送失败",
                ),
            )

            val unitTransferred = entry(
                key = "levelselect.linkedMaps.unitTransferred",
                translations = linkedMapOf(
                    "en" to "Unit transferred to {0}",
                    "zh-CN" to "单位已传送至 {0}",
                ),
            )

        }

        object loadError {
            val message = entry(
                key = "levelselect.loadError.message",
                translations = linkedMapOf(
                    "en" to "Unable to load the selected map.",
                    "zh-CN" to "无法加载所选地图。",
                ),
            )

            val title = entry(
                key = "levelselect.loadError.title",
                translations = linkedMapOf(
                    "en" to "Map Load Error",
                    "zh-CN" to "地图加载失败",
                ),
            )

        }

        val mapType = entry(
            key = "levelselect.mapType",
            translations = linkedMapOf(
                "en" to "Map Type",
                "zh-CN" to "地图类型",
            ),
        )

        object mode {
            val areaControl = entry(
                key = "levelselect.mode.areaControl",
                translations = linkedMapOf(
                    "en" to "Area Control",
                    "zh-CN" to "区域控制",
                ),
            )

            val mapLinks = entry(
                key = "levelselect.mode.mapLinks",
                translations = linkedMapOf(
                    "en" to "Map Links",
                    "zh-CN" to "地图联通",
                ),
            )

        }

        val noMatches = entry(
            key = "levelselect.noMatches",
            translations = linkedMapOf(
                "en" to "No maps match the current filter",
                "zh-CN" to "没有符合筛选条件的地图",
            ),
        )

        val noSavedGames = entry(
            key = "levelselect.noSavedGames",
            translations = linkedMapOf(
                "en" to "No saved games found",
                "zh-CN" to "未找到存档",
            ),
        )

        val playerCount = entry(
            key = "levelselect.playerCount",
            translations = linkedMapOf(
                "en" to "{0}p",
                "zh-CN" to "{0} 人",
            ),
        )

        val playerMaps = entry(
            key = "levelselect.playerMaps",
            translations = linkedMapOf(
                "en" to "{0}p maps",
                "zh-CN" to "{0} 人地图",
            ),
        )

        val players = entry(
            key = "levelselect.players",
            translations = linkedMapOf(
                "en" to "Players",
                "zh-CN" to "人数",
            ),
        )

        val rwxxModes = entry(
            key = "levelselect.rwxxModes",
            translations = linkedMapOf(
                "en" to "RWXX modes",
                "zh-CN" to "RWXX 模式",
            ),
        )

        val scenario = entry(
            key = "levelselect.scenario",
            translations = linkedMapOf(
                "en" to "Scenario",
                "zh-CN" to "剧情地图",
            ),
        )

        val searchHint = entry(
            key = "levelselect.searchHint",
            translations = linkedMapOf(
                "en" to "Map name",
                "zh-CN" to "地图名称",
            ),
        )

        object sort : I18nNode(
            entry(
                key = "levelselect.sort",
                translations = linkedMapOf(
                    "en" to "Sort",
                    "zh-CN" to "排序",
                ),
            )
        ) {
            val defaultOrder = entry(
                key = "levelselect.sort.defaultOrder",
                translations = linkedMapOf(
                    "en" to "Default",
                    "zh-CN" to "默认",
                ),
            )

            val name = entry(
                key = "levelselect.sort.name",
                translations = linkedMapOf(
                    "en" to "Name",
                    "zh-CN" to "名称",
                ),
            )

            val players = entry(
                key = "levelselect.sort.players",
                translations = linkedMapOf(
                    "en" to "Players",
                    "zh-CN" to "人数",
                ),
            )

        }

    }

    object loading {
        val autoScroll = entry(
            key = "loading.autoScroll",
            translations = linkedMapOf(
                "en" to "AUTO SCROLL",
                "zh-CN" to "自动滚动",
            ),
        )

        val eventLog = entry(
            key = "loading.eventLog",
            translations = linkedMapOf(
                "en" to "BOOT / EVENT LOG",
                "zh-CN" to "启动 / 事件日志",
            ),
        )

        val label = entry(
            key = "loading.label",
            translations = linkedMapOf(
                "en" to "Loading...",
                "zh-CN" to "正在加载...",
            ),
        )

    }

    object mainmenu {
        object about : I18nNode(
            entry(
                key = "mainmenu.about",
                translations = linkedMapOf(
                    "en" to "About",
                    "zh-CN" to "关于",
                ),
            )
        ) {
            val discord = entry(
                key = "mainmenu.about.discord",
                translations = linkedMapOf(
                    "en" to "Discord",
                    "zh-CN" to "Discord",
                ),
            )

            val github = entry(
                key = "mainmenu.about.github",
                translations = linkedMapOf(
                    "en" to "GitHub",
                    "zh-CN" to "GitHub",
                ),
            )

            val license = entry(
                key = "mainmenu.about.license",
                translations = linkedMapOf(
                    "en" to "License",
                    "zh-CN" to "许可证",
                ),
            )

            val licenseValue = entry(
                key = "mainmenu.about.licenseValue",
                translations = linkedMapOf(
                    "en" to "GNU AGPL-3.0",
                    "zh-CN" to "GNU AGPL-3.0",
                ),
            )

            val message = entry(
                key = "mainmenu.about.message",
                translations = linkedMapOf(
                    "en" to "Rusted Warfare Extension\nRebuilding and extending Rusted Warfare as an open-source cross-platform RTS game.",
                    "zh-CN" to "Rusted Warfare Extension\n重建和扩展 Rusted Warfare 的开源跨平台 RTS 游戏。",
                ),
            )

            val openLinkFailed = entry(
                key = "mainmenu.about.openLinkFailed",
                translations = linkedMapOf(
                    "en" to "Unable to open link: {0}",
                    "zh-CN" to "无法打开链接：{0}",
                ),
            )

            val qq = entry(
                key = "mainmenu.about.qq",
                translations = linkedMapOf(
                    "en" to "QQ",
                    "zh-CN" to "QQ",
                ),
            )

            val title = entry(
                key = "mainmenu.about.title",
                translations = linkedMapOf(
                    "en" to "About RWXX",
                    "zh-CN" to "关于 RWXX",
                ),
            )

            val version = entry(
                key = "mainmenu.about.version",
                translations = linkedMapOf(
                    "en" to "Version",
                    "zh-CN" to "版本",
                ),
            )

            val versionValue = entry(
                key = "mainmenu.about.versionValue",
                translations = linkedMapOf(
                    "en" to "{0}({1}) CompatibleCore:{2}",
                    "zh-CN" to "{0}({1}) 兼容内核:{2}",
                ),
            )

        }

        object badge {
            val about = entry(
                key = "mainmenu.badge.about",
                translations = linkedMapOf(
                    "en" to "INFO",
                    "zh-CN" to "关于",
                ),
            )

            val exit = entry(
                key = "mainmenu.badge.exit",
                translations = linkedMapOf(
                    "en" to "EXIT",
                    "zh-CN" to "退出",
                ),
            )

            val mods = entry(
                key = "mainmenu.badge.mods",
                translations = linkedMapOf(
                    "en" to "MOD",
                    "zh-CN" to "模组",
                ),
            )

            val multiplayer = entry(
                key = "mainmenu.badge.multiplayer",
                translations = linkedMapOf(
                    "en" to "MP",
                    "zh-CN" to "联机",
                ),
            )

            val p2p = entry(
                key = "mainmenu.badge.p2p",
                translations = linkedMapOf(
                    "en" to "P2P",
                    "zh-CN" to "直连",
                ),
            )

            val replay = entry(
                key = "mainmenu.badge.replay",
                translations = linkedMapOf(
                    "en" to "REP",
                    "zh-CN" to "回放",
                ),
            )

            val resources = entry(
                key = "mainmenu.badge.resources",
                translations = linkedMapOf(
                    "en" to "RES",
                    "zh-CN" to "资源",
                ),
            )

            val resume = entry(
                key = "mainmenu.badge.resume",
                translations = linkedMapOf(
                    "en" to "GO",
                    "zh-CN" to "继续",
                ),
            )

            val sandbox = entry(
                key = "mainmenu.badge.sandbox",
                translations = linkedMapOf(
                    "en" to "SAN",
                    "zh-CN" to "沙盒",
                ),
            )

            val settings = entry(
                key = "mainmenu.badge.settings",
                translations = linkedMapOf(
                    "en" to "SET",
                    "zh-CN" to "设置",
                ),
            )

            val singlePlayer = entry(
                key = "mainmenu.badge.singlePlayer",
                translations = linkedMapOf(
                    "en" to "SP",
                    "zh-CN" to "单人",
                ),
            )

        }

        val `continue` = entry(
            key = "mainmenu.continue",
            translations = linkedMapOf(
                "en" to "Continue",
                "zh-CN" to "继续",
            ),
        )

        val exit = entry(
            key = "mainmenu.exit",
            translations = linkedMapOf(
                "en" to "Exit",
                "zh-CN" to "退出",
            ),
        )

        val help = entry(
            key = "mainmenu.help",
            translations = linkedMapOf(
                "en" to "Help",
                "zh-CN" to "帮助",
            ),
        )

        val mods = entry(
            key = "mainmenu.mods",
            translations = linkedMapOf(
                "en" to "Mods",
                "zh-CN" to "模组",
            ),
        )

        val multiplayer = entry(
            key = "mainmenu.multiplayer",
            translations = linkedMapOf(
                "en" to "Multiplayer",
                "zh-CN" to "多人游戏",
            ),
        )

        val p2pMultiplayer = entry(
            key = "mainmenu.p2pMultiplayer",
            translations = linkedMapOf(
                "en" to "P2P Multiplayer",
                "zh-CN" to "P2P联机",
            ),
        )

        val resourceBrowser = entry(
            key = "mainmenu.resourceBrowser",
            translations = linkedMapOf(
                "en" to "Resource Browser",
                "zh-CN" to "资源浏览器",
            ),
        )

        val settings = entry(
            key = "mainmenu.settings",
            translations = linkedMapOf(
                "en" to "Settings",
                "zh-CN" to "设置",
            ),
        )

        val singlePlayer = entry(
            key = "mainmenu.singlePlayer",
            translations = linkedMapOf(
                "en" to "Single Player",
                "zh-CN" to "单人游戏",
            ),
        )

        val watchReplay = entry(
            key = "mainmenu.watchReplay",
            translations = linkedMapOf(
                "en" to "Watch Replay",
                "zh-CN" to "观看回放",
            ),
        )

    }

    object modWindow {
        val back = entry(
            key = "modWindow.back",
            translations = linkedMapOf(
                "en" to "Back",
                "zh-CN" to "返回",
            ),
        )

        val emptyMessage = entry(
            key = "modWindow.emptyMessage",
            translations = linkedMapOf(
                "en" to "No mod window is currently open.",
                "zh-CN" to "当前没有打开模组窗口。",
            ),
        )

        val emptyTitle = entry(
            key = "modWindow.emptyTitle",
            translations = linkedMapOf(
                "en" to "Mod Window",
                "zh-CN" to "模组窗口",
            ),
        )

    }

    object mods {
        val apply = entry(
            key = "mods.apply",
            translations = linkedMapOf(
                "en" to "Apply",
                "zh-CN" to "应用",
            ),
        )

        val author = entry(
            key = "mods.author",
            translations = linkedMapOf(
                "en" to "Author: {0}",
                "zh-CN" to "作者：{0}",
            ),
        )

        val back = entry(
            key = "mods.back",
            translations = linkedMapOf(
                "en" to "Back",
                "zh-CN" to "返回",
            ),
        )

        val chooseFile = entry(
            key = "mods.chooseFile",
            translations = linkedMapOf(
                "en" to "Choose file",
                "zh-CN" to "选择文件",
            ),
        )

        val chooseFileOrDirectory = entry(
            key = "mods.chooseFileOrDirectory",
            translations = linkedMapOf(
                "en" to "Choose a mod file or directory",
                "zh-CN" to "选择模组文件或文件夹",
            ),
        )

        val delete = entry(
            key = "mods.delete",
            translations = linkedMapOf(
                "en" to "Delete",
                "zh-CN" to "删除",
            ),
        )

        val deleteFailed = entry(
            key = "mods.deleteFailed",
            translations = linkedMapOf(
                "en" to "Unable to delete mod",
                "zh-CN" to "无法删除模组",
            ),
        )

        val description = entry(
            key = "mods.description",
            translations = linkedMapOf(
                "en" to "Description",
                "zh-CN" to "描述",
            ),
        )

        val disable = entry(
            key = "mods.disable",
            translations = linkedMapOf(
                "en" to "Disable",
                "zh-CN" to "禁用",
            ),
        )

        val disableAll = entry(
            key = "mods.disableAll",
            translations = linkedMapOf(
                "en" to "Disable All",
                "zh-CN" to "全部禁用",
            ),
        )

        val disabled = entry(
            key = "mods.disabled",
            translations = linkedMapOf(
                "en" to "Disabled",
                "zh-CN" to "未启用",
            ),
        )

        val empty = entry(
            key = "mods.empty",
            translations = linkedMapOf(
                "en" to "No mods installed",
                "zh-CN" to "未安装模组",
            ),
        )

        val enable = entry(
            key = "mods.enable",
            translations = linkedMapOf(
                "en" to "Enable",
                "zh-CN" to "启用",
            ),
        )

        val enabled = entry(
            key = "mods.enabled",
            translations = linkedMapOf(
                "en" to "Enabled",
                "zh-CN" to "已启用",
            ),
        )

        val error = entry(
            key = "mods.error",
            translations = linkedMapOf(
                "en" to "Error",
                "zh-CN" to "错误",
            ),
        )

        val filter = entry(
            key = "mods.filter",
            translations = linkedMapOf(
                "en" to "Filter",
                "zh-CN" to "筛选",
            ),
        )

        val importFile = entry(
            key = "mods.importFile",
            translations = linkedMapOf(
                "en" to "Import",
                "zh-CN" to "导入",
            ),
        )

        val importPathHint = entry(
            key = "mods.importPathHint",
            translations = linkedMapOf(
                "en" to "/path/to/mod.rwmod",
                "zh-CN" to "模组或证书的文件路径",
            ),
        )

        val importPrompt = entry(
            key = "mods.importPrompt",
            translations = linkedMapOf(
                "en" to "Enter a mod, asset key, author trust certificate, or license path.",
                "zh-CN" to "请输入模组、资源密钥、作者信任证书或许可证的路径。",
            ),
        )

        val importTitle = entry(
            key = "mods.importTitle",
            translations = linkedMapOf(
                "en" to "Import Mod",
                "zh-CN" to "导入模组",
            ),
        )

        val loadingUnitData = entry(
            key = "mods.loadingUnitData",
            translations = linkedMapOf(
                "en" to "Loading custom unit data",
                "zh-CN" to "正在加载自定义单位数据",
            ),
        )

        val more = entry(
            key = "mods.more",
            translations = linkedMapOf(
                "en" to "More...",
                "zh-CN" to "更多...",
            ),
        )

        val reload = entry(
            key = "mods.reload",
            translations = linkedMapOf(
                "en" to "Reload",
                "zh-CN" to "重新加载",
            ),
        )

        val reloadFailed = entry(
            key = "mods.reloadFailed",
            translations = linkedMapOf(
                "en" to "Unable to reload mods: {0}",
                "zh-CN" to "无法重新加载模组：{0}",
            ),
        )

        val reloaded = entry(
            key = "mods.reloaded",
            translations = linkedMapOf(
                "en" to "Mods reloaded",
                "zh-CN" to "模组已重新加载",
            ),
        )

        val reloading = entry(
            key = "mods.reloading",
            translations = linkedMapOf(
                "en" to "Reloading Mods",
                "zh-CN" to "正在重新加载模组",
            ),
        )

        val title = entry(
            key = "mods.title",
            translations = linkedMapOf(
                "en" to "Mods",
                "zh-CN" to "模组",
            ),
        )

        val updateFailed = entry(
            key = "mods.updateFailed",
            translations = linkedMapOf(
                "en" to "Unable to update mods: {0}",
                "zh-CN" to "无法更新模组：{0}",
            ),
        )

        val version = entry(
            key = "mods.version",
            translations = linkedMapOf(
                "en" to "Version: {0}",
                "zh-CN" to "版本：{0}",
            ),
        )

    }

    object multiplayer {
        val anyVersion = entry(
            key = "multiplayer.anyVersion",
            translations = linkedMapOf(
                "en" to "ANY",
                "zh-CN" to "不限版本",
            ),
        )

        val checkConnection = entry(
            key = "multiplayer.checkConnection",
            translations = linkedMapOf(
                "en" to "Unable to reach the server.\nPlease check your connection and try again.",
                "zh-CN" to "无法连接服务器。\n请检查网络连接后重试。",
            ),
        )

        val configurePlayerName = entry(
            key = "multiplayer.configurePlayerName",
            translations = linkedMapOf(
                "en" to "Player Name",
                "zh-CN" to "玩家名称设置",
            ),
        )

        val connectingTo = entry(
            key = "multiplayer.connectingTo",
            translations = linkedMapOf(
                "en" to "Connecting to {0}...",
                "zh-CN" to "正在连接到 {0}...",
            ),
        )

        val connectionError = entry(
            key = "multiplayer.connectionError",
            translations = linkedMapOf(
                "en" to "Connection Error",
                "zh-CN" to "连接错误",
            ),
        )

        object filterAccess {
            val all = entry(
                key = "multiplayer.filterAccess.all",
                translations = linkedMapOf(
                    "en" to "Any access",
                    "zh-CN" to "不限密码",
                ),
            )

            val `open` = entry(
                key = "multiplayer.filterAccess.open",
                translations = linkedMapOf(
                    "en" to "No password",
                    "zh-CN" to "无密码",
                ),
            )

            val password = entry(
                key = "multiplayer.filterAccess.password",
                translations = linkedMapOf(
                    "en" to "Password",
                    "zh-CN" to "需要密码",
                ),
            )

        }

        object filterMods {
            val all = entry(
                key = "multiplayer.filterMods.all",
                translations = linkedMapOf(
                    "en" to "Any mods",
                    "zh-CN" to "全部模组",
                ),
            )

            val none = entry(
                key = "multiplayer.filterMods.none",
                translations = linkedMapOf(
                    "en" to "No mods",
                    "zh-CN" to "无模组",
                ),
            )

            val required = entry(
                key = "multiplayer.filterMods.required",
                translations = linkedMapOf(
                    "en" to "Has mods",
                    "zh-CN" to "有模组",
                ),
            )

        }

        object filterSlots {
            val all = entry(
                key = "multiplayer.filterSlots.all",
                translations = linkedMapOf(
                    "en" to "Any slots",
                    "zh-CN" to "全部人数",
                ),
            )

            val `open` = entry(
                key = "multiplayer.filterSlots.open",
                translations = linkedMapOf(
                    "en" to "Open slots",
                    "zh-CN" to "未满",
                ),
            )

        }

        object filterStatus {
            val all = entry(
                key = "multiplayer.filterStatus.all",
                translations = linkedMapOf(
                    "en" to "Any status",
                    "zh-CN" to "全部状态",
                ),
            )

            val inGame = entry(
                key = "multiplayer.filterStatus.inGame",
                translations = linkedMapOf(
                    "en" to "In game",
                    "zh-CN" to "游戏中",
                ),
            )

            val other = entry(
                key = "multiplayer.filterStatus.other",
                translations = linkedMapOf(
                    "en" to "Other",
                    "zh-CN" to "其他",
                ),
            )

            val waiting = entry(
                key = "multiplayer.filterStatus.waiting",
                translations = linkedMapOf(
                    "en" to "Waiting",
                    "zh-CN" to "等待中",
                ),
            )

        }

        val hostGame = entry(
            key = "multiplayer.hostGame",
            translations = linkedMapOf(
                "en" to "Host Game",
                "zh-CN" to "创建游戏",
            ),
        )

        val hostGameInfo = entry(
            key = "multiplayer.hostGameInfo",
            translations = linkedMapOf(
                "en" to "Configure the server before opening the battle room.\nMap: {0}",
                "zh-CN" to "在打开战役室前配置服务器。\n地图：{0}",
            ),
        )

        val hostP2pGameInfo = entry(
            key = "multiplayer.hostP2pGameInfo",
            translations = linkedMapOf(
                "en" to "Configure the P2P room before opening the battle room.\nMap: {0}",
                "zh-CN" to "在打开战役室前配置P2P房间。\n地图：{0}",
            ),
        )

        val hostPassword = entry(
            key = "multiplayer.hostPassword",
            translations = linkedMapOf(
                "en" to "Password",
                "zh-CN" to "密码",
            ),
        )

        val hostPasswordHint = entry(
            key = "multiplayer.hostPasswordHint",
            translations = linkedMapOf(
                "en" to "Optional",
                "zh-CN" to "可选",
            ),
        )

        val hostStartP2p = entry(
            key = "multiplayer.hostStartP2p",
            translations = linkedMapOf(
                "en" to "Host P2P",
                "zh-CN" to "创建P2P房间",
            ),
        )

        val hostStartPrivate = entry(
            key = "multiplayer.hostStartPrivate",
            translations = linkedMapOf(
                "en" to "Host Private",
                "zh-CN" to "私有创建",
            ),
        )

        val hostStartPublic = entry(
            key = "multiplayer.hostStartPublic",
            translations = linkedMapOf(
                "en" to "Host Public",
                "zh-CN" to "公开创建",
            ),
        )

        val hostUseMods = entry(
            key = "multiplayer.hostUseMods",
            translations = linkedMapOf(
                "en" to "Use active mods",
                "zh-CN" to "使用已启用的模组",
            ),
        )

        val joinP2pRoom = entry(
            key = "multiplayer.joinP2pRoom",
            translations = linkedMapOf(
                "en" to "Join P2P Room",
                "zh-CN" to "加入P2P房间",
            ),
        )

        val joinP2pRoomInput = entry(
            key = "multiplayer.joinP2pRoomInput",
            translations = linkedMapOf(
                "en" to "Enter the P2P room id.",
                "zh-CN" to "输入P2P房间ID。",
            ),
        )

        val joinServer = entry(
            key = "multiplayer.joinServer",
            translations = linkedMapOf(
                "en" to "Join Server",
                "zh-CN" to "加入服务器",
            ),
        )

        val joinServerHint = entry(
            key = "multiplayer.joinServerHint",
            translations = linkedMapOf(
                "en" to "server address or room code",
                "zh-CN" to "服务器地址或房间代码",
            ),
        )

        val joinServerInput = entry(
            key = "multiplayer.joinServerInput",
            translations = linkedMapOf(
                "en" to "Enter the server address or room code.",
                "zh-CN" to "输入服务器地址或房间代码。",
            ),
        )

        val joinServerQuestion = entry(
            key = "multiplayer.joinServerQuestion",
            translations = linkedMapOf(
                "en" to "Join Server?",
                "zh-CN" to "加入服务器？",
            ),
        )

        val joiningRoom = entry(
            key = "multiplayer.joiningRoom",
            translations = linkedMapOf(
                "en" to "Joining Room",
                "zh-CN" to "正在加入房间",
            ),
        )

        object lobby {
            val original = entry(
                key = "multiplayer.lobby.original",
                translations = linkedMapOf(
                    "en" to "Original RW",
                    "zh-CN" to "原版联机",
                ),
            )

            val originalUnavailable = entry(
                key = "multiplayer.lobby.originalUnavailable",
                translations = linkedMapOf(
                    "en" to "Original lobby unavailable: {0}",
                    "zh-CN" to "原版大厅暂不可用：{0}",
                ),
            )

            val p2p = entry(
                key = "multiplayer.lobby.p2p",
                translations = linkedMapOf(
                    "en" to "RWXX P2P",
                    "zh-CN" to "RWXX 点对点联机",
                ),
            )

            val p2pUnavailable = entry(
                key = "multiplayer.lobby.p2pUnavailable",
                translations = linkedMapOf(
                    "en" to "P2P unavailable: {0}",
                    "zh-CN" to "点对点联机暂不可用：{0}",
                ),
            )

            val requiresEngine = entry(
                key = "multiplayer.lobby.requiresEngine",
                translations = linkedMapOf(
                    "en" to "Original lobby requires the RW engine to be loaded",
                    "zh-CN" to "原版大厅需要先加载游戏引擎",
                ),
            )

        }

        val missingJoinInput = entry(
            key = "multiplayer.missingJoinInput",
            translations = linkedMapOf(
                "en" to "Unable to join server: missing server address or room code",
                "zh-CN" to "无法加入服务器：缺少服务器地址或房间代码",
            ),
        )

        val missingPlayerName = entry(
            key = "multiplayer.missingPlayerName",
            translations = linkedMapOf(
                "en" to "Player name cannot be empty",
                "zh-CN" to "玩家名称不能为空",
            ),
        )

        val modMarker = entry(
            key = "multiplayer.modMarker",
            translations = linkedMapOf(
                "en" to "M",
                "zh-CN" to "模",
            ),
        )

        val noMatches = entry(
            key = "multiplayer.noMatches",
            translations = linkedMapOf(
                "en" to "No rooms match the current filter",
                "zh-CN" to "没有匹配的房间",
            ),
        )

        val noPreviousGame = entry(
            key = "multiplayer.noPreviousGame",
            translations = linkedMapOf(
                "en" to "No previous game address is available",
                "zh-CN" to "没有可重新连接的上次游戏地址",
            ),
        )

        val noRooms = entry(
            key = "multiplayer.noRooms",
            translations = linkedMapOf(
                "en" to "No rooms found",
                "zh-CN" to "未找到房间",
            ),
        )

        val p2pRoomIdHint = entry(
            key = "multiplayer.p2pRoomIdHint",
            translations = linkedMapOf(
                "en" to "room id",
                "zh-CN" to "房间ID",
            ),
        )

        val passwordMarker = entry(
            key = "multiplayer.passwordMarker",
            translations = linkedMapOf(
                "en" to "P",
                "zh-CN" to "密",
            ),
        )

        val playerNameHint = entry(
            key = "multiplayer.playerNameHint",
            translations = linkedMapOf(
                "en" to "player name",
                "zh-CN" to "玩家名称",
            ),
        )

        val playerNamePrompt = entry(
            key = "multiplayer.playerNamePrompt",
            translations = linkedMapOf(
                "en" to "Enter the name shown to other players.",
                "zh-CN" to "输入向其他玩家显示的名称。",
            ),
        )

        val refresh = entry(
            key = "multiplayer.refresh",
            translations = linkedMapOf(
                "en" to "Refresh",
                "zh-CN" to "刷新",
            ),
        )

        val rejoinLastGame = entry(
            key = "multiplayer.rejoinLastGame",
            translations = linkedMapOf(
                "en" to "Return to Last Game",
                "zh-CN" to "回到上一次游戏",
            ),
        )

        object roomInfo {
            val dataChannel = entry(
                key = "multiplayer.roomInfo.dataChannel",
                translations = linkedMapOf(
                    "en" to "WebRTC DataChannel: available",
                    "zh-CN" to "WebRTC 数据通道：可用",
                ),
            )

            val directFallback = entry(
                key = "multiplayer.roomInfo.directFallback",
                translations = linkedMapOf(
                    "en" to "Direct fallback: available",
                    "zh-CN" to "直连备用通道：可用",
                ),
            )

            val discovery = entry(
                key = "multiplayer.roomInfo.discovery",
                translations = linkedMapOf(
                    "en" to "Discovery: {0}",
                    "zh-CN" to "发现来源：{0}",
                ),
            )

            val features = entry(
                key = "multiplayer.roomInfo.features",
                translations = linkedMapOf(
                    "en" to "RWXX Features: {0}",
                    "zh-CN" to "RWXX 扩展功能：{0}",
                ),
            )

            val host = entry(
                key = "multiplayer.roomInfo.host",
                translations = linkedMapOf(
                    "en" to "Host: {0}",
                    "zh-CN" to "主机：{0}",
                ),
            )

            val iceServers = entry(
                key = "multiplayer.roomInfo.iceServers",
                translations = linkedMapOf(
                    "en" to "ICE Servers:",
                    "zh-CN" to "ICE 服务器：",
                ),
            )

            val lan = entry(
                key = "multiplayer.roomInfo.lan",
                translations = linkedMapOf(
                    "en" to "Lan: {0}",
                    "zh-CN" to "局域网地址：{0}",
                ),
            )

            val map = entry(
                key = "multiplayer.roomInfo.map",
                translations = linkedMapOf(
                    "en" to "Map: {0}",
                    "zh-CN" to "地图：{0}",
                ),
            )

            val mods = entry(
                key = "multiplayer.roomInfo.mods",
                translations = linkedMapOf(
                    "en" to "Mods: {0}",
                    "zh-CN" to "模组：{0}",
                ),
            )

            val modsNeeded = entry(
                key = "multiplayer.roomInfo.modsNeeded",
                translations = linkedMapOf(
                    "en" to "Mods Needed: {0}",
                    "zh-CN" to "所需模组：{0}",
                ),
            )

            val password = entry(
                key = "multiplayer.roomInfo.password",
                translations = linkedMapOf(
                    "en" to "Password: {0}",
                    "zh-CN" to "密码：{0}",
                ),
            )

            val passwordRequired = entry(
                key = "multiplayer.roomInfo.passwordRequired",
                translations = linkedMapOf(
                    "en" to "Password Required",
                    "zh-CN" to "需要密码",
                ),
            )

            val players = entry(
                key = "multiplayer.roomInfo.players",
                translations = linkedMapOf(
                    "en" to "Players: {0}",
                    "zh-CN" to "玩家：{0}",
                ),
            )

            val portClosed = entry(
                key = "multiplayer.roomInfo.portClosed",
                translations = linkedMapOf(
                    "en" to "Port: not open (Connecting over the internet may fail)",
                    "zh-CN" to "端口未开放（互联网连接可能失败）",
                ),
            )

            val state = entry(
                key = "multiplayer.roomInfo.state",
                translations = linkedMapOf(
                    "en" to "State: {0}",
                    "zh-CN" to "状态：{0}",
                ),
            )

            val transport = entry(
                key = "multiplayer.roomInfo.transport",
                translations = linkedMapOf(
                    "en" to "Transport: {0}",
                    "zh-CN" to "传输：{0}",
                ),
            )

            val url = entry(
                key = "multiplayer.roomInfo.url",
                translations = linkedMapOf(
                    "en" to "Url: {0}",
                    "zh-CN" to "链接：{0}",
                ),
            )

            val version = entry(
                key = "multiplayer.roomInfo.version",
                translations = linkedMapOf(
                    "en" to "Version: {0}",
                    "zh-CN" to "版本：{0}",
                ),
            )

            val versionMismatch = entry(
                key = "multiplayer.roomInfo.versionMismatch",
                translations = linkedMapOf(
                    "en" to "{0} (different game version!)",
                    "zh-CN" to "{0}（游戏版本不同）",
                ),
            )

        }

        val roomNoLongerListed = entry(
            key = "multiplayer.roomNoLongerListed",
            translations = linkedMapOf(
                "en" to "Unable to join server: room is no longer in the list",
                "zh-CN" to "无法加入服务器：房间已不在列表中",
            ),
        )

        val searchHint = entry(
            key = "multiplayer.searchHint",
            translations = linkedMapOf(
                "en" to "host or map",
                "zh-CN" to "房主或地图",
            ),
        )

        val searchingRooms = entry(
            key = "multiplayer.searchingRooms",
            translations = linkedMapOf(
                "en" to "Searching for rooms...",
                "zh-CN" to "正在搜索房间…",
            ),
        )

        val server = entry(
            key = "multiplayer.server",
            translations = linkedMapOf(
                "en" to "server",
                "zh-CN" to "服务器",
            ),
        )

        object state {
            val chat = entry(
                key = "multiplayer.state.chat",
                translations = linkedMapOf(
                    "en" to "Chat",
                    "zh-CN" to "聊天中",
                ),
            )

            val locked = entry(
                key = "multiplayer.state.locked",
                translations = linkedMapOf(
                    "en" to "Locked",
                    "zh-CN" to "已锁定",
                ),
            )

        }

        val title = entry(
            key = "multiplayer.title",
            translations = linkedMapOf(
                "en" to "Multiplayer Rooms",
                "zh-CN" to "多人游戏房间",
            ),
        )

        object transport {
            val dedicated = entry(
                key = "multiplayer.transport.dedicated",
                translations = linkedMapOf(
                    "en" to "Dedicated",
                    "zh-CN" to "专用服务器",
                ),
            )

            val internet = entry(
                key = "multiplayer.transport.internet",
                translations = linkedMapOf(
                    "en" to "Internet",
                    "zh-CN" to "互联网",
                ),
            )

            val lan = entry(
                key = "multiplayer.transport.lan",
                translations = linkedMapOf(
                    "en" to "LAN",
                    "zh-CN" to "局域网",
                ),
            )

            val relay = entry(
                key = "multiplayer.transport.relay",
                translations = linkedMapOf(
                    "en" to "Relay",
                    "zh-CN" to "中继",
                ),
            )

        }

        val unableToJoinP2pRoom = entry(
            key = "multiplayer.unableToJoinP2pRoom",
            translations = linkedMapOf(
                "en" to "Unable to join P2P room",
                "zh-CN" to "无法加入P2P房间",
            ),
        )

        val unableToJoinServer = entry(
            key = "multiplayer.unableToJoinServer",
            translations = linkedMapOf(
                "en" to "Unable to join server",
                "zh-CN" to "无法加入服务器",
            ),
        )

        val unknown = entry(
            key = "multiplayer.unknown",
            translations = linkedMapOf(
                "en" to "Unknown",
                "zh-CN" to "未知",
            ),
        )

    }

    object pausemenu {
        val chat = entry(
            key = "pausemenu.chat",
            translations = linkedMapOf(
                "en" to "Chat",
                "zh-CN" to "聊天",
            ),
        )

        val command = entry(
            key = "pausemenu.command",
            translations = linkedMapOf(
                "en" to "RWXX  //  COMMAND",
                "zh-CN" to "RWXX  //  指挥中心",
            ),
        )

        val exitGame = entry(
            key = "pausemenu.exitGame",
            translations = linkedMapOf(
                "en" to "Exit Game",
                "zh-CN" to "退出游戏",
            ),
        )

        val players = entry(
            key = "pausemenu.players",
            translations = linkedMapOf(
                "en" to "Players",
                "zh-CN" to "玩家",
            ),
        )

        val resume = entry(
            key = "pausemenu.resume",
            translations = linkedMapOf(
                "en" to "Resume",
                "zh-CN" to "继续",
            ),
        )

        val save = entry(
            key = "pausemenu.save",
            translations = linkedMapOf(
                "en" to "Save",
                "zh-CN" to "保存",
            ),
        )

        val settings = entry(
            key = "pausemenu.settings",
            translations = linkedMapOf(
                "en" to "Settings",
                "zh-CN" to "设置",
            ),
        )

        val startRecording = entry(
            key = "pausemenu.startRecording",
            translations = linkedMapOf(
                "en" to "Start Recording",
                "zh-CN" to "开始录制",
            ),
        )

        val stopRecording = entry(
            key = "pausemenu.stopRecording",
            translations = linkedMapOf(
                "en" to "Stop Recording",
                "zh-CN" to "停止录制",
            ),
        )

        val surrender = entry(
            key = "pausemenu.surrender",
            translations = linkedMapOf(
                "en" to "Surrender",
                "zh-CN" to "投降",
            ),
        )

        val title = entry(
            key = "pausemenu.title",
            translations = linkedMapOf(
                "en" to "Tactical Command",
                "zh-CN" to "战术指挥",
            ),
        )

    }

    object replay {
        val empty = entry(
            key = "replay.empty",
            translations = linkedMapOf(
                "en" to "No replays found",
                "zh-CN" to "未找到回放",
            ),
        )

        val filter = entry(
            key = "replay.filter",
            translations = linkedMapOf(
                "en" to "Filter",
                "zh-CN" to "筛选",
            ),
        )

        val noMatches = entry(
            key = "replay.noMatches",
            translations = linkedMapOf(
                "en" to "No replays match the current filter",
                "zh-CN" to "没有符合筛选条件的回放",
            ),
        )

        val searchHint = entry(
            key = "replay.searchHint",
            translations = linkedMapOf(
                "en" to "replay name",
                "zh-CN" to "回放名称",
            ),
        )

        val title = entry(
            key = "replay.title",
            translations = linkedMapOf(
                "en" to "Watch Replay",
                "zh-CN" to "观看回放",
            ),
        )

        val unknownSize = entry(
            key = "replay.unknownSize",
            translations = linkedMapOf(
                "en" to "Unknown size",
                "zh-CN" to "大小未知",
            ),
        )

        val unknownTime = entry(
            key = "replay.unknownTime",
            translations = linkedMapOf(
                "en" to "Unknown time",
                "zh-CN" to "时间未知",
            ),
        )

    }

    object resourcebrowser {
        val author = entry(
            key = "resourcebrowser.author",
            translations = linkedMapOf(
                "en" to "Author: {0}",
                "zh-CN" to "作者：{0}",
            ),
        )

        val back = entry(
            key = "resourcebrowser.back",
            translations = linkedMapOf(
                "en" to "Back",
                "zh-CN" to "返回",
            ),
        )

        val download = entry(
            key = "resourcebrowser.download",
            translations = linkedMapOf(
                "en" to "Download",
                "zh-CN" to "下载",
            ),
        )

        val downloadDone = entry(
            key = "resourcebrowser.downloadDone",
            translations = linkedMapOf(
                "en" to "Download complete",
                "zh-CN" to "下载完成",
            ),
        )

        val downloadDoneMessage = entry(
            key = "resourcebrowser.downloadDoneMessage",
            translations = linkedMapOf(
                "en" to "The resource has been saved.",
                "zh-CN" to "资源已保存。",
            ),
        )

        val downloadFailed = entry(
            key = "resourcebrowser.downloadFailed",
            translations = linkedMapOf(
                "en" to "Download failed: {0}",
                "zh-CN" to "下载失败：{0}",
            ),
        )

        val downloading = entry(
            key = "resourcebrowser.downloading",
            translations = linkedMapOf(
                "en" to "Downloading",
                "zh-CN" to "正在下载",
            ),
        )

        val downloads = entry(
            key = "resourcebrowser.downloads",
            translations = linkedMapOf(
                "en" to "Downloads: {0}",
                "zh-CN" to "下载次数：{0}",
            ),
        )

        val empty = entry(
            key = "resourcebrowser.empty",
            translations = linkedMapOf(
                "en" to "No resources found",
                "zh-CN" to "没有找到资源",
            ),
        )

        val failed = entry(
            key = "resourcebrowser.failed",
            translations = linkedMapOf(
                "en" to "Unable to load resources: {0}",
                "zh-CN" to "无法加载资源：{0}",
            ),
        )

        val loadMore = entry(
            key = "resourcebrowser.loadMore",
            translations = linkedMapOf(
                "en" to "Load More",
                "zh-CN" to "加载更多",
            ),
        )

        val loading = entry(
            key = "resourcebrowser.loading",
            translations = linkedMapOf(
                "en" to "Loading resources...",
                "zh-CN" to "正在加载资源...",
            ),
        )

        val missingDownloadUrl = entry(
            key = "resourcebrowser.missingDownloadUrl",
            translations = linkedMapOf(
                "en" to "This resource does not provide a download link.",
                "zh-CN" to "此资源没有提供下载链接。",
            ),
        )

        val `open` = entry(
            key = "resourcebrowser.open",
            translations = linkedMapOf(
                "en" to "Open",
                "zh-CN" to "打开",
            ),
        )

        val resourceType = entry(
            key = "resourcebrowser.resourceType",
            translations = linkedMapOf(
                "en" to "Resource Type",
                "zh-CN" to "资源类型",
            ),
        )

        val search = entry(
            key = "resourcebrowser.search",
            translations = linkedMapOf(
                "en" to "Search",
                "zh-CN" to "搜索",
            ),
        )

        val searchHint = entry(
            key = "resourcebrowser.searchHint",
            translations = linkedMapOf(
                "en" to "keyword",
                "zh-CN" to "关键词",
            ),
        )

        val sourceSearch = entry(
            key = "resourcebrowser.sourceSearch",
            translations = linkedMapOf(
                "en" to "RTSBox Search",
                "zh-CN" to "铁锈盒子搜索",
            ),
        )

        val sourceWeekly = entry(
            key = "resourcebrowser.sourceWeekly",
            translations = linkedMapOf(
                "en" to "Weekly Downloads",
                "zh-CN" to "下载周榜",
            ),
        )

        val title = entry(
            key = "resourcebrowser.title",
            translations = linkedMapOf(
                "en" to "Resource Browser",
                "zh-CN" to "资源浏览器",
            ),
        )

        val typeMap = entry(
            key = "resourcebrowser.typeMap",
            translations = linkedMapOf(
                "en" to "Map",
                "zh-CN" to "地图",
            ),
        )

        val typeMod = entry(
            key = "resourcebrowser.typeMod",
            translations = linkedMapOf(
                "en" to "Mod",
                "zh-CN" to "模组",
            ),
        )

        val version = entry(
            key = "resourcebrowser.version",
            translations = linkedMapOf(
                "en" to "Version: {0}",
                "zh-CN" to "版本：{0}",
            ),
        )

    }

    object settings {
        object audio {
            val enableSounds = entry(
                key = "settings.audio.enableSounds",
                translations = linkedMapOf(
                    "en" to "Enable game sounds",
                    "zh-CN" to "启用游戏声音",
                ),
            )

            val gameVolume = entry(
                key = "settings.audio.gameVolume",
                translations = linkedMapOf(
                    "en" to "Game volume",
                    "zh-CN" to "游戏音效",
                ),
            )

            val interfaceVolume = entry(
                key = "settings.audio.interfaceVolume",
                translations = linkedMapOf(
                    "en" to "Interface volume",
                    "zh-CN" to "界面音效",
                ),
            )

            val masterVolume = entry(
                key = "settings.audio.masterVolume",
                translations = linkedMapOf(
                    "en" to "Master volume",
                    "zh-CN" to "主音量",
                ),
            )

            val musicVolume = entry(
                key = "settings.audio.musicVolume",
                translations = linkedMapOf(
                    "en" to "Music volume",
                    "zh-CN" to "音乐音量",
                ),
            )

        }

        object colorScheme {
            val hint = entry(
                key = "settings.colorScheme.hint",
                translations = linkedMapOf(
                    "en" to "These colors apply to the game HUD and in-game mod windows. Menus and the pause panel use the fixed cyberpunk theme.",
                    "zh-CN" to "配色用于游戏内操作界面和模组窗口。菜单和暂停面板使用固定的赛博朋克主题。",
                ),
            )

            object names {
                val amberBlue = entry(
                    key = "settings.colorScheme.names.amberBlue",
                    translations = linkedMapOf(
                        "en" to "Amber Blue",
                        "zh-CN" to "琥珀蓝",
                    ),
                )

                val aquaBlue = entry(
                    key = "settings.colorScheme.names.aquaBlue",
                    translations = linkedMapOf(
                        "en" to "Aqua Blue",
                        "zh-CN" to "水蓝",
                    ),
                )

                val bahamaAndTrinidad = entry(
                    key = "settings.colorScheme.names.bahamaAndTrinidad",
                    translations = linkedMapOf(
                        "en" to "Bahama And Trinidad",
                        "zh-CN" to "海蓝暖橙",
                    ),
                )

                val blumine = entry(
                    key = "settings.colorScheme.names.blumine",
                    translations = linkedMapOf(
                        "en" to "Blumine",
                        "zh-CN" to "深海蓝",
                    ),
                )

                val flutterDash = entry(
                    key = "settings.colorScheme.names.flutterDash",
                    translations = linkedMapOf(
                        "en" to "Flutter Dash",
                        "zh-CN" to "轻羽天蓝",
                    ),
                )

                val goldSunset = entry(
                    key = "settings.colorScheme.names.goldSunset",
                    translations = linkedMapOf(
                        "en" to "Gold Sunset",
                        "zh-CN" to "金色落日",
                    ),
                )

                val greenMoney = entry(
                    key = "settings.colorScheme.names.greenMoney",
                    translations = linkedMapOf(
                        "en" to "Green Money",
                        "zh-CN" to "翡翠绿",
                    ),
                )

                val greyLaw = entry(
                    key = "settings.colorScheme.names.greyLaw",
                    translations = linkedMapOf(
                        "en" to "Grey Law",
                        "zh-CN" to "沉稳灰",
                    ),
                )

                val hippieBlue = entry(
                    key = "settings.colorScheme.names.hippieBlue",
                    translations = linkedMapOf(
                        "en" to "Hippie Blue",
                        "zh-CN" to "嬉皮蓝",
                    ),
                )

                val materialDefault = entry(
                    key = "settings.colorScheme.names.materialDefault",
                    translations = linkedMapOf(
                        "en" to "Material Default",
                        "zh-CN" to "质感默认",
                    ),
                )

                val materialPurple = entry(
                    key = "settings.colorScheme.names.materialPurple",
                    translations = linkedMapOf(
                        "en" to "Material Purple",
                        "zh-CN" to "质感紫",
                    ),
                )

                val pinkSakura = entry(
                    key = "settings.colorScheme.names.pinkSakura",
                    translations = linkedMapOf(
                        "en" to "Pink Sakura",
                        "zh-CN" to "樱花粉",
                    ),
                )

                val redTornado = entry(
                    key = "settings.colorScheme.names.redTornado",
                    translations = linkedMapOf(
                        "en" to "Red Tornado",
                        "zh-CN" to "红色风暴",
                    ),
                )

                val rosewood = entry(
                    key = "settings.colorScheme.names.rosewood",
                    translations = linkedMapOf(
                        "en" to "Rosewood",
                        "zh-CN" to "玫瑰木",
                    ),
                )

                val rwx = entry(
                    key = "settings.colorScheme.names.rwx",
                    translations = linkedMapOf(
                        "en" to "RWXX",
                        "zh-CN" to "铁锈经典",
                    ),
                )

                val verdunLime = entry(
                    key = "settings.colorScheme.names.verdunLime",
                    translations = linkedMapOf(
                        "en" to "Verdun Lime",
                        "zh-CN" to "青柠绿",
                    ),
                )

            }

        }

        val current = entry(
            key = "settings.current",
            translations = linkedMapOf(
                "en" to "Current language: {0}",
                "zh-CN" to "当前语言：{0}",
            ),
        )

        object display {
            val adaptiveBattleVisuals = entry(
                key = "settings.display.adaptiveBattleVisuals",
                translations = linkedMapOf(
                    "en" to "Simplify visuals in large battles (Kool)",
                    "zh-CN" to "大规模战斗时简化画面",
                ),
            )

            val batterySaving = entry(
                key = "settings.display.batterySaving",
                translations = linkedMapOf(
                    "en" to "Battery saving mode (30 FPS limit)",
                    "zh-CN" to "省电模式（限制为 30 帧/秒）",
                ),
            )

            val edgeScrollSpeed = entry(
                key = "settings.display.edgeScrollSpeed",
                translations = linkedMapOf(
                    "en" to "Edge scroll speed",
                    "zh-CN" to "边缘滚动速度",
                ),
            )

            val experimentalAndroidOpenGlRenderer = entry(
                key = "settings.display.experimentalAndroidOpenGlRenderer",
                translations = linkedMapOf(
                    "en" to "Use experimental OpenGL renderer",
                    "zh-CN" to "使用实验性 OpenGL 渲染",
                ),
            )

            val framesPerSecond = entry(
                key = "settings.display.framesPerSecond",
                translations = linkedMapOf(
                    "en" to "{0} FPS",
                    "zh-CN" to "{0} 帧/秒",
                ),
            )

            val fullscreen = entry(
                key = "settings.display.fullscreen",
                translations = linkedMapOf(
                    "en" to "Fullscreen",
                    "zh-CN" to "全屏",
                ),
            )

            val highRefreshRate = entry(
                key = "settings.display.highRefreshRate",
                translations = linkedMapOf(
                    "en" to "High refresh rate",
                    "zh-CN" to "高刷新率",
                ),
            )

            val maxFrameRate = entry(
                key = "settings.display.maxFrameRate",
                translations = linkedMapOf(
                    "en" to "Maximum frame rate",
                    "zh-CN" to "帧率上限",
                ),
            )

            val maxFrameRateAuto = entry(
                key = "settings.display.maxFrameRateAuto",
                translations = linkedMapOf(
                    "en" to "Auto (300 FPS)",
                    "zh-CN" to "自动（300 帧/秒）",
                ),
            )

            val renderClouds = entry(
                key = "settings.display.renderClouds",
                translations = linkedMapOf(
                    "en" to "Render clouds",
                    "zh-CN" to "渲染云层",
                ),
            )

            val renderDoubleScale = entry(
                key = "settings.display.renderDoubleScale",
                translations = linkedMapOf(
                    "en" to "Double scale rendering",
                    "zh-CN" to "渲染半分辨率",
                ),
            )

            val scrollSpeed = entry(
                key = "settings.display.scrollSpeed",
                translations = linkedMapOf(
                    "en" to "Scroll speed",
                    "zh-CN" to "滚动速度",
                ),
            )

            val shaderEffects = entry(
                key = "settings.display.shaderEffects",
                translations = linkedMapOf(
                    "en" to "Shader effects",
                    "zh-CN" to "着色器效果",
                ),
            )

            val showFps = entry(
                key = "settings.display.showFps",
                translations = linkedMapOf(
                    "en" to "Show FPS",
                    "zh-CN" to "显示帧率",
                ),
            )

            val showHpChanges = entry(
                key = "settings.display.showHpChanges",
                translations = linkedMapOf(
                    "en" to "Show HP changes",
                    "zh-CN" to "显示血量变化",
                ),
            )

            val showMainMenuBackgroundDemo = entry(
                key = "settings.display.showMainMenuBackgroundDemo",
                translations = linkedMapOf(
                    "en" to "Show main menu battle demo",
                    "zh-CN" to "显示主菜单战斗演示",
                ),
            )

            val showUnitHp = entry(
                key = "settings.display.showUnitHp",
                translations = linkedMapOf(
                    "en" to "Show unit HP",
                    "zh-CN" to "显示单位血量",
                ),
            )

            val showUnitIcons = entry(
                key = "settings.display.showUnitIcons",
                translations = linkedMapOf(
                    "en" to "Show unit icons",
                    "zh-CN" to "显示单位图标",
                ),
            )

            val showWarLogOnScreen = entry(
                key = "settings.display.showWarLogOnScreen",
                translations = linkedMapOf(
                    "en" to "Show war log on screen",
                    "zh-CN" to "屏幕显示战斗日志",
                ),
            )

            val showWaypoints = entry(
                key = "settings.display.showWaypoints",
                translations = linkedMapOf(
                    "en" to "Show unit waypoints",
                    "zh-CN" to "显示单位路径点",
                ),
            )

            val showZoomButton = entry(
                key = "settings.display.showZoomButton",
                translations = linkedMapOf(
                    "en" to "Show zoom button",
                    "zh-CN" to "显示缩放按钮",
                ),
            )

            val softFogFading = entry(
                key = "settings.display.softFogFading",
                translations = linkedMapOf(
                    "en" to "Soft fog fading",
                    "zh-CN" to "软雾渐变",
                ),
            )

            val teamShaders = entry(
                key = "settings.display.teamShaders",
                translations = linkedMapOf(
                    "en" to "Team color shaders",
                    "zh-CN" to "团队颜色着色器",
                ),
            )

            val uiScale = entry(
                key = "settings.display.uiScale",
                translations = linkedMapOf(
                    "en" to "Interface scale (HUD and build menu)",
                    "zh-CN" to "界面缩放（操作界面与建造菜单）",
                ),
            )

            val useMinimapAllyColors = entry(
                key = "settings.display.useMinimapAllyColors",
                translations = linkedMapOf(
                    "en" to "Use minimap ally colors",
                    "zh-CN" to "使用小地图友军颜色",
                ),
            )

            val vsync = entry(
                key = "settings.display.vsync",
                translations = linkedMapOf(
                    "en" to "VSync",
                    "zh-CN" to "垂直同步",
                ),
            )

        }

        object gameplay {
            val allFactoriesProduction = entry(
                key = "settings.gameplay.allFactoriesProduction",
                translations = linkedMapOf(
                    "en" to "Produce in all selected factories",
                    "zh-CN" to "所有选中的工厂同时生产",
                ),
            )

            val autosaving = entry(
                key = "settings.gameplay.autosaving",
                translations = linkedMapOf(
                    "en" to "Autosaving",
                    "zh-CN" to "自动保存",
                ),
            )

            val doubleClickToAttackMove = entry(
                key = "settings.gameplay.doubleClickToAttackMove",
                translations = linkedMapOf(
                    "en" to "Double click to attack move",
                    "zh-CN" to "双击攻击移动",
                ),
            )

            val factoryProduction = entry(
                key = "settings.gameplay.factoryProduction",
                translations = linkedMapOf(
                    "en" to "Factory production",
                    "zh-CN" to "工厂生产方式",
                ),
            )

            val quickRally = entry(
                key = "settings.gameplay.quickRally",
                translations = linkedMapOf(
                    "en" to "Quick rally",
                    "zh-CN" to "快速集合",
                ),
            )

            val replaysShowRecordedChat = entry(
                key = "settings.gameplay.replaysShowRecordedChat",
                translations = linkedMapOf(
                    "en" to "Show recorded chat",
                    "zh-CN" to "显示聊天记录",
                ),
            )

            val saveMultiplayerReplays = entry(
                key = "settings.gameplay.saveMultiplayerReplays",
                translations = linkedMapOf(
                    "en" to "Save multiplayer replays",
                    "zh-CN" to "保存多人录像",
                ),
            )

            val shiftDigitUpdatesGroup = entry(
                key = "settings.gameplay.shiftDigitUpdatesGroup",
                translations = linkedMapOf(
                    "en" to "Shift+number merges selection and updates the group",
                    "zh-CN" to "Shift+数字合并选择并更新编组",
                ),
            )

            val showChatAndPingShortcuts = entry(
                key = "settings.gameplay.showChatAndPingShortcuts",
                translations = linkedMapOf(
                    "en" to "Show chat and ping shortcuts",
                    "zh-CN" to "显示聊天和信号快捷键",
                ),
            )

            val showMapPingsOnBattlefield = entry(
                key = "settings.gameplay.showMapPingsOnBattlefield",
                translations = linkedMapOf(
                    "en" to "Show map pings on battlefield",
                    "zh-CN" to "战场显示地图信号",
                ),
            )

            val showMapPingsOnMinimap = entry(
                key = "settings.gameplay.showMapPingsOnMinimap",
                translations = linkedMapOf(
                    "en" to "Show map pings on minimap",
                    "zh-CN" to "小地图显示地图信号",
                ),
            )

            val showPlayerChatInGame = entry(
                key = "settings.gameplay.showPlayerChatInGame",
                translations = linkedMapOf(
                    "en" to "Show player chat in game",
                    "zh-CN" to "游戏内显示玩家聊天",
                ),
            )

            val singleUnitProduction = entry(
                key = "settings.gameplay.singleUnitProduction",
                translations = linkedMapOf(
                    "en" to "Produce one unit at a time (choose an idle factory first)",
                    "zh-CN" to "每次生产一个单位（优先分配给空闲工厂）",
                ),
            )

            val smartSelection = entry(
                key = "settings.gameplay.smartSelection",
                translations = linkedMapOf(
                    "en" to "Smart selection",
                    "zh-CN" to "智能选择",
                ),
            )

            val udpInMultiplayer = entry(
                key = "settings.gameplay.udpInMultiplayer",
                translations = linkedMapOf(
                    "en" to "UDP networking for lower lag (beta)",
                    "zh-CN" to "使用UDP协议降低网络延迟（实验性功能）",
                ),
            )

        }

        object `interface` {
            val classicInterface = entry(
                key = "settings.interface.classicInterface",
                translations = linkedMapOf(
                    "en" to "Classic interface",
                    "zh-CN" to "经典界面",
                ),
            )

            val forceEnglish = entry(
                key = "settings.interface.forceEnglish",
                translations = linkedMapOf(
                    "en" to "Force English",
                    "zh-CN" to "强制英文",
                ),
            )

            val gestureZoom = entry(
                key = "settings.interface.gestureZoom",
                translations = linkedMapOf(
                    "en" to "Enable 3 finger zoom gesture",
                    "zh-CN" to "启用3指缩放手势",
                ),
            )

            val immersiveFullScreen = entry(
                key = "settings.interface.immersiveFullScreen",
                translations = linkedMapOf(
                    "en" to "Immersive fullscreen",
                    "zh-CN" to "沉浸式全屏",
                ),
            )

            val keyboardSupport = entry(
                key = "settings.interface.keyboardSupport",
                translations = linkedMapOf(
                    "en" to "Enable keyboard support",
                    "zh-CN" to "启用键盘支持",
                ),
            )

            val mouseCapture = entry(
                key = "settings.interface.mouseCapture",
                translations = linkedMapOf(
                    "en" to "Mouse capture",
                    "zh-CN" to "鼠标捕获",
                ),
            )

            val mouseSupport = entry(
                key = "settings.interface.mouseSupport",
                translations = linkedMapOf(
                    "en" to "Enable extended mouse support",
                    "zh-CN" to "启用扩展鼠标支持",
                ),
            )

            val sendReports = entry(
                key = "settings.interface.sendReports",
                translations = linkedMapOf(
                    "en" to "Send crash reports",
                    "zh-CN" to "发送崩溃报告",
                ),
            )

            val showUnitGroups = entry(
                key = "settings.interface.showUnitGroups",
                translations = linkedMapOf(
                    "en" to "Show unit group interface",
                    "zh-CN" to "显示单位编组界面",
                ),
            )

            val unlockedScreenRotation = entry(
                key = "settings.interface.unlockedScreenRotation",
                translations = linkedMapOf(
                    "en" to "Allow portrait screen rotation",
                    "zh-CN" to "允许纵向屏幕旋转",
                ),
            )

            val useCircleSelect = entry(
                key = "settings.interface.useCircleSelect",
                translations = linkedMapOf(
                    "en" to "Hold touch to area select",
                    "zh-CN" to "启用长按选区模式",
                ),
            )

        }

        object keyBindings {
            object actions {
                val actionSetRally = entry(
                    key = "settings.keyBindings.actions.actionSetRally",
                    translations = linkedMapOf(
                        "en" to "Action - Set Rally",
                        "zh-CN" to "设置集结点",
                    ),
                )

                val actionUpgrade = entry(
                    key = "settings.keyBindings.actions.actionUpgrade",
                    translations = linkedMapOf(
                        "en" to "Action - Upgrade",
                        "zh-CN" to "升级",
                    ),
                )

                val addGroupToSelection = entry(
                    key = "settings.keyBindings.actions.addGroupToSelection",
                    translations = linkedMapOf(
                        "en" to "Add group to selection {0}",
                        "zh-CN" to "将编组 {0} 加入当前选择",
                    ),
                )

                val attackMove = entry(
                    key = "settings.keyBindings.actions.attackMove",
                    translations = linkedMapOf(
                        "en" to "Attack Move",
                        "zh-CN" to "攻击移动",
                    ),
                )

                val cameraDown = entry(
                    key = "settings.keyBindings.actions.cameraDown",
                    translations = linkedMapOf(
                        "en" to "Camera Down",
                        "zh-CN" to "镜头下移",
                    ),
                )

                val cameraLeft = entry(
                    key = "settings.keyBindings.actions.cameraLeft",
                    translations = linkedMapOf(
                        "en" to "Camera Left",
                        "zh-CN" to "镜头左移",
                    ),
                )

                val cameraRight = entry(
                    key = "settings.keyBindings.actions.cameraRight",
                    translations = linkedMapOf(
                        "en" to "Camera Right",
                        "zh-CN" to "镜头右移",
                    ),
                )

                val cameraUp = entry(
                    key = "settings.keyBindings.actions.cameraUp",
                    translations = linkedMapOf(
                        "en" to "Camera Up",
                        "zh-CN" to "镜头上移",
                    ),
                )

                val createGroup = entry(
                    key = "settings.keyBindings.actions.createGroup",
                    translations = linkedMapOf(
                        "en" to "Create group {0}",
                        "zh-CN" to "创建编组 {0}",
                    ),
                )

                val cycleAirFactories = entry(
                    key = "settings.keyBindings.actions.cycleAirFactories",
                    translations = linkedMapOf(
                        "en" to "Cycle Air Factories",
                        "zh-CN" to "切换空军工厂",
                    ),
                )

                val cycleBuilders = entry(
                    key = "settings.keyBindings.actions.cycleBuilders",
                    translations = linkedMapOf(
                        "en" to "Cycle Builders",
                        "zh-CN" to "切换建造者",
                    ),
                )

                val cycleExtractors = entry(
                    key = "settings.keyBindings.actions.cycleExtractors",
                    translations = linkedMapOf(
                        "en" to "Cycle Extractors",
                        "zh-CN" to "切换资源抽取器",
                    ),
                )

                val cycleLandFactories = entry(
                    key = "settings.keyBindings.actions.cycleLandFactories",
                    translations = linkedMapOf(
                        "en" to "Cycle Land Factories",
                        "zh-CN" to "切换陆军工厂",
                    ),
                )

                val cycleUpgradableFabricators = entry(
                    key = "settings.keyBindings.actions.cycleUpgradableFabricators",
                    translations = linkedMapOf(
                        "en" to "Cycle Upgradable Fabricators",
                        "zh-CN" to "切换可升级的资源制造仪",
                    ),
                )

                val deselectUnits = entry(
                    key = "settings.keyBindings.actions.deselectUnits",
                    translations = linkedMapOf(
                        "en" to "Deselect units",
                        "zh-CN" to "取消选择单位",
                    ),
                )

                val faster = entry(
                    key = "settings.keyBindings.actions.faster",
                    translations = linkedMapOf(
                        "en" to "Faster",
                        "zh-CN" to "提高速度",
                    ),
                )

                val gameSpeed = entry(
                    key = "settings.keyBindings.actions.gameSpeed",
                    translations = linkedMapOf(
                        "en" to "Game Speed (Single player)",
                        "zh-CN" to "游戏速度（单人游戏）",
                    ),
                )

                val goToNotification = entry(
                    key = "settings.keyBindings.actions.goToNotification",
                    translations = linkedMapOf(
                        "en" to "Go to notification",
                        "zh-CN" to "跳转到通知位置",
                    ),
                )

                val guardUnit = entry(
                    key = "settings.keyBindings.actions.guardUnit",
                    translations = linkedMapOf(
                        "en" to "Guard Unit",
                        "zh-CN" to "保护单位",
                    ),
                )

                val nextMusicTrack = entry(
                    key = "settings.keyBindings.actions.nextMusicTrack",
                    translations = linkedMapOf(
                        "en" to "Next Music Track",
                        "zh-CN" to "下一首音乐",
                    ),
                )

                val patrol = entry(
                    key = "settings.keyBindings.actions.patrol",
                    translations = linkedMapOf(
                        "en" to "Patrol",
                        "zh-CN" to "巡逻",
                    ),
                )

                val pauseGame = entry(
                    key = "settings.keyBindings.actions.pauseGame",
                    translations = linkedMapOf(
                        "en" to "Pause Game",
                        "zh-CN" to "暂停游戏",
                    ),
                )

                val pingMap = entry(
                    key = "settings.keyBindings.actions.pingMap",
                    translations = linkedMapOf(
                        "en" to "Ping Map",
                        "zh-CN" to "发送地图信号",
                    ),
                )

                val reclaim = entry(
                    key = "settings.keyBindings.actions.reclaim",
                    translations = linkedMapOf(
                        "en" to "Reclaim",
                        "zh-CN" to "回收",
                    ),
                )

                val saveGame = entry(
                    key = "settings.keyBindings.actions.saveGame",
                    translations = linkedMapOf(
                        "en" to "Save Game",
                        "zh-CN" to "保存游戏",
                    ),
                )

                val selectCommandCenter = entry(
                    key = "settings.keyBindings.actions.selectCommandCenter",
                    translations = linkedMapOf(
                        "en" to "Select Command Center",
                        "zh-CN" to "选择指挥中心",
                    ),
                )

                val selectGroup = entry(
                    key = "settings.keyBindings.actions.selectGroup",
                    translations = linkedMapOf(
                        "en" to "Select group {0}",
                        "zh-CN" to "选择编组 {0}",
                    ),
                )

                val selectWholeArmy = entry(
                    key = "settings.keyBindings.actions.selectWholeArmy",
                    translations = linkedMapOf(
                        "en" to "Select Whole Army",
                        "zh-CN" to "选择全部军队",
                    ),
                )

                val sendChat = entry(
                    key = "settings.keyBindings.actions.sendChat",
                    translations = linkedMapOf(
                        "en" to "Send Chat",
                        "zh-CN" to "发送聊天消息",
                    ),
                )

                val sendTeamChat = entry(
                    key = "settings.keyBindings.actions.sendTeamChat",
                    translations = linkedMapOf(
                        "en" to "Send Team Chat",
                        "zh-CN" to "发送队伍消息",
                    ),
                )

                val showMenu = entry(
                    key = "settings.keyBindings.actions.showMenu",
                    translations = linkedMapOf(
                        "en" to "Show Menu",
                        "zh-CN" to "显示菜单",
                    ),
                )

                val slower = entry(
                    key = "settings.keyBindings.actions.slower",
                    translations = linkedMapOf(
                        "en" to "Slower",
                        "zh-CN" to "降低速度",
                    ),
                )

                val stop = entry(
                    key = "settings.keyBindings.actions.stop",
                    translations = linkedMapOf(
                        "en" to "Stop",
                        "zh-CN" to "停止",
                    ),
                )

                val unitAction = entry(
                    key = "settings.keyBindings.actions.unitAction",
                    translations = linkedMapOf(
                        "en" to "Unit action {0}",
                        "zh-CN" to "单位操作 {0}",
                    ),
                )

                val unitActions = entry(
                    key = "settings.keyBindings.actions.unitActions",
                    translations = linkedMapOf(
                        "en" to "Unit Actions",
                        "zh-CN" to "单位操作",
                    ),
                )

                val unitGroups = entry(
                    key = "settings.keyBindings.actions.unitGroups",
                    translations = linkedMapOf(
                        "en" to "Unit Groups",
                        "zh-CN" to "单位编组",
                    ),
                )

                val zoomIn = entry(
                    key = "settings.keyBindings.actions.zoomIn",
                    translations = linkedMapOf(
                        "en" to "Zoom In",
                        "zh-CN" to "放大视野",
                    ),
                )

                val zoomOut = entry(
                    key = "settings.keyBindings.actions.zoomOut",
                    translations = linkedMapOf(
                        "en" to "Zoom Out",
                        "zh-CN" to "缩小视野",
                    ),
                )

            }

            val hint = entry(
                key = "settings.keyBindings.hint",
                translations = linkedMapOf(
                    "en" to "Click a binding slot, then press the replacement key. Escape cancels capture.",
                    "zh-CN" to "点击按键栏后按下要绑定的按键，按 Esc 取消。红色边框表示按键冲突。",
                ),
            )

            object keys {
                val backspace = entry(
                    key = "settings.keyBindings.keys.backspace",
                    translations = linkedMapOf(
                        "en" to "Backspace",
                        "zh-CN" to "退格",
                    ),
                )

                val capsLock = entry(
                    key = "settings.keyBindings.keys.capsLock",
                    translations = linkedMapOf(
                        "en" to "Caps Lock",
                        "zh-CN" to "大写锁定",
                    ),
                )

                val delete = entry(
                    key = "settings.keyBindings.keys.delete",
                    translations = linkedMapOf(
                        "en" to "Delete",
                        "zh-CN" to "删除",
                    ),
                )

                val down = entry(
                    key = "settings.keyBindings.keys.down",
                    translations = linkedMapOf(
                        "en" to "Down",
                        "zh-CN" to "下方向键",
                    ),
                )

                val end = entry(
                    key = "settings.keyBindings.keys.end",
                    translations = linkedMapOf(
                        "en" to "End",
                        "zh-CN" to "结束键",
                    ),
                )

                val enter = entry(
                    key = "settings.keyBindings.keys.enter",
                    translations = linkedMapOf(
                        "en" to "Enter",
                        "zh-CN" to "回车",
                    ),
                )

                val escape = entry(
                    key = "settings.keyBindings.keys.escape",
                    translations = linkedMapOf(
                        "en" to "Esc",
                        "zh-CN" to "Esc",
                    ),
                )

                val home = entry(
                    key = "settings.keyBindings.keys.home",
                    translations = linkedMapOf(
                        "en" to "Home",
                        "zh-CN" to "起始键",
                    ),
                )

                val insert = entry(
                    key = "settings.keyBindings.keys.insert",
                    translations = linkedMapOf(
                        "en" to "Insert",
                        "zh-CN" to "插入",
                    ),
                )

                val left = entry(
                    key = "settings.keyBindings.keys.left",
                    translations = linkedMapOf(
                        "en" to "Left",
                        "zh-CN" to "左方向键",
                    ),
                )

                val numLock = entry(
                    key = "settings.keyBindings.keys.numLock",
                    translations = linkedMapOf(
                        "en" to "Num Lock",
                        "zh-CN" to "数字锁定",
                    ),
                )

                val numpad = entry(
                    key = "settings.keyBindings.keys.numpad",
                    translations = linkedMapOf(
                        "en" to "Numpad {0}",
                        "zh-CN" to "小键盘 {0}",
                    ),
                )

                val pageDown = entry(
                    key = "settings.keyBindings.keys.pageDown",
                    translations = linkedMapOf(
                        "en" to "Page Down",
                        "zh-CN" to "向下翻页",
                    ),
                )

                val pageUp = entry(
                    key = "settings.keyBindings.keys.pageUp",
                    translations = linkedMapOf(
                        "en" to "Page Up",
                        "zh-CN" to "向上翻页",
                    ),
                )

                val pause = entry(
                    key = "settings.keyBindings.keys.pause",
                    translations = linkedMapOf(
                        "en" to "Pause",
                        "zh-CN" to "暂停键",
                    ),
                )

                val right = entry(
                    key = "settings.keyBindings.keys.right",
                    translations = linkedMapOf(
                        "en" to "Right",
                        "zh-CN" to "右方向键",
                    ),
                )

                val scrollLock = entry(
                    key = "settings.keyBindings.keys.scrollLock",
                    translations = linkedMapOf(
                        "en" to "Scroll Lock",
                        "zh-CN" to "滚动锁定",
                    ),
                )

                val space = entry(
                    key = "settings.keyBindings.keys.space",
                    translations = linkedMapOf(
                        "en" to "Space",
                        "zh-CN" to "空格",
                    ),
                )

                val tab = entry(
                    key = "settings.keyBindings.keys.tab",
                    translations = linkedMapOf(
                        "en" to "Tab",
                        "zh-CN" to "制表键",
                    ),
                )

                val unknown = entry(
                    key = "settings.keyBindings.keys.unknown",
                    translations = linkedMapOf(
                        "en" to "Unknown",
                        "zh-CN" to "未知按键",
                    ),
                )

                val up = entry(
                    key = "settings.keyBindings.keys.up",
                    translations = linkedMapOf(
                        "en" to "Up",
                        "zh-CN" to "上方向键",
                    ),
                )

            }

            val pressKey = entry(
                key = "settings.keyBindings.pressKey",
                translations = linkedMapOf(
                    "en" to "Press a key…",
                    "zh-CN" to "请按键…",
                ),
            )

            val unassigned = entry(
                key = "settings.keyBindings.unassigned",
                translations = linkedMapOf(
                    "en" to "None",
                    "zh-CN" to "未绑定",
                ),
            )

        }

        object language {
            val label = entry(
                key = "settings.language.label",
                translations = linkedMapOf(
                    "en" to "UI language",
                    "zh-CN" to "界面语言",
                ),
            )

        }

        object pages {
            val audio = entry(
                key = "settings.pages.audio",
                translations = linkedMapOf(
                    "en" to "Audio",
                    "zh-CN" to "声音",
                ),
            )

            val colorScheme = entry(
                key = "settings.pages.colorScheme",
                translations = linkedMapOf(
                    "en" to "In-game Color Scheme",
                    "zh-CN" to "游戏内配色方案",
                ),
            )

            val display = entry(
                key = "settings.pages.display",
                translations = linkedMapOf(
                    "en" to "Display & Input",
                    "zh-CN" to "显示与输入",
                ),
            )

            val gameplay = entry(
                key = "settings.pages.gameplay",
                translations = linkedMapOf(
                    "en" to "Gameplay",
                    "zh-CN" to "游戏",
                ),
            )

            val `interface` = entry(
                key = "settings.pages.interface",
                translations = linkedMapOf(
                    "en" to "Interface",
                    "zh-CN" to "界面",
                ),
            )

            val keyBindings = entry(
                key = "settings.pages.keyBindings",
                translations = linkedMapOf(
                    "en" to "Key Bindings",
                    "zh-CN" to "按键绑定",
                ),
            )

        }

        object storage {
            val `external` = entry(
                key = "settings.storage.external",
                translations = linkedMapOf(
                    "en" to "External storage",
                    "zh-CN" to "外部存储",
                ),
            )

            val externalShort = entry(
                key = "settings.storage.externalShort",
                translations = linkedMapOf(
                    "en" to "External",
                    "zh-CN" to "外部",
                ),
            )

            val `internal` = entry(
                key = "settings.storage.internal",
                translations = linkedMapOf(
                    "en" to "Internal storage",
                    "zh-CN" to "内部存储",
                ),
            )

            val internalShort = entry(
                key = "settings.storage.internalShort",
                translations = linkedMapOf(
                    "en" to "Internal",
                    "zh-CN" to "内部",
                ),
            )

            val location = entry(
                key = "settings.storage.location",
                translations = linkedMapOf(
                    "en" to "Storage location",
                    "zh-CN" to "存储位置",
                ),
            )

        }

        object tabs {
            val colorScheme = entry(
                key = "settings.tabs.colorScheme",
                translations = linkedMapOf(
                    "en" to "Game Colors",
                    "zh-CN" to "配色",
                ),
            )

            val display = entry(
                key = "settings.tabs.display",
                translations = linkedMapOf(
                    "en" to "Display",
                    "zh-CN" to "显示",
                ),
            )

            val keyBindings = entry(
                key = "settings.tabs.keyBindings",
                translations = linkedMapOf(
                    "en" to "Keys",
                    "zh-CN" to "按键",
                ),
            )

        }

    }

    object singleplayer {
        val back = entry(
            key = "singleplayer.back",
            translations = linkedMapOf(
                "en" to "Back",
                "zh-CN" to "返回",
            ),
        )

        val campaign = entry(
            key = "singleplayer.campaign",
            translations = linkedMapOf(
                "en" to "Campaign",
                "zh-CN" to "战役",
            ),
        )

        val challenge = entry(
            key = "singleplayer.challenge",
            translations = linkedMapOf(
                "en" to "Challenge",
                "zh-CN" to "挑战",
            ),
        )

        val customMaps = entry(
            key = "singleplayer.customMaps",
            translations = linkedMapOf(
                "en" to "Custom Maps",
                "zh-CN" to "自定义地图",
            ),
        )

        val loadSave = entry(
            key = "singleplayer.loadSave",
            translations = linkedMapOf(
                "en" to "Load Save",
                "zh-CN" to "读取存档",
            ),
        )

        val noPreview = entry(
            key = "singleplayer.noPreview",
            translations = linkedMapOf(
                "en" to "No preview",
                "zh-CN" to "暂无预览",
            ),
        )

        val sandbox = entry(
            key = "singleplayer.sandbox",
            translations = linkedMapOf(
                "en" to "Sandbox",
                "zh-CN" to "沙盒",
            ),
        )

        val skirmish = entry(
            key = "singleplayer.skirmish",
            translations = linkedMapOf(
                "en" to "Skirmish",
                "zh-CN" to "遭遇战",
            ),
        )

        val survival = entry(
            key = "singleplayer.survival",
            translations = linkedMapOf(
                "en" to "Survival",
                "zh-CN" to "生存",
            ),
        )

    }

    object techtree {
        val available = entry(
            key = "techtree.available",
            translations = linkedMapOf(
                "en" to "Available",
                "zh-CN" to "可解锁",
            ),
        )

        val back = entry(
            key = "techtree.back",
            translations = linkedMapOf(
                "en" to "Back to Game",
                "zh-CN" to "返回游戏",
            ),
        )

        val costAndPrerequisites = entry(
            key = "techtree.costAndPrerequisites",
            translations = linkedMapOf(
                "en" to "Cost: {0}  Prerequisites: {1}",
                "zh-CN" to "费用：{0}  前置：{1}",
            ),
        )

        val empty = entry(
            key = "techtree.empty",
            translations = linkedMapOf(
                "en" to "No technology trees are registered",
                "zh-CN" to "暂无已注册科技树",
            ),
        )

        val locked = entry(
            key = "techtree.locked",
            translations = linkedMapOf(
                "en" to "Locked",
                "zh-CN" to "未解锁",
            ),
        )

        val requirementsMissing = entry(
            key = "techtree.requirementsMissing",
            translations = linkedMapOf(
                "en" to "Requirements Missing",
                "zh-CN" to "条件不足",
            ),
        )

        val syncHint = entry(
            key = "techtree.syncHint",
            translations = linkedMapOf(
                "en" to "Unlock status is synchronized for the team and saved with the game.",
                "zh-CN" to "解锁状态与队伍同步，并会写入存档。",
            ),
        )

        val title = entry(
            key = "techtree.title",
            translations = linkedMapOf(
                "en" to "Technology Tree",
                "zh-CN" to "科技树",
            ),
        )

        val unlock = entry(
            key = "techtree.unlock",
            translations = linkedMapOf(
                "en" to "Unlock",
                "zh-CN" to "解锁",
            ),
        )

        val unlockRequested = entry(
            key = "techtree.unlockRequested",
            translations = linkedMapOf(
                "en" to "Unlock requested: {0}",
                "zh-CN" to "已发送解锁请求：{0}",
            ),
        )

        val unlocked = entry(
            key = "techtree.unlocked",
            translations = linkedMapOf(
                "en" to "Unlocked",
                "zh-CN" to "已解锁",
            ),
        )

    }

}
