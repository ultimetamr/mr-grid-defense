# MR空间塔防：九宫格坚守

一款基于 PICO Spatial SDK 0.13.3 的混合现实九宫格塔防游戏。玩家在真实空间中校准战场、布置六种防御塔抵御十波怪物，并在随机安全格之间移动以躲避红色能量射线。

## 工具链

- PICO Spatial SDK BOM 0.13.3
- Android Gradle Plugin 8.13.2
- Kotlin 2.1.20
- compileSdk / targetSdk / minSdk：35 / 35 / 35
- Java/Kotlin JVM target：11

Spatial SDK 0.13.3 官方模板依赖 API 35 与 Kotlin 2.x metadata，因此工程保持官方兼容工具链。若必须使用 API 33 / Kotlin 1.9，需要同时选择兼容的旧版 Spatial SDK 并重新验证 API。

## 核心系统

- `SpatialManager`：主地面、活动边界和真实空间障碍查询。
- `SceneLayoutManager`：生成布局几何、塔区和怪物生成边界。
- `WallManager`：城墙状态、贴地实体、受击反馈、升级、修复与破墙结算。
- `GoldManager`：唯一金币账本，集中处理获取、消费、非负校验和事件通知。
- `TowerManager`：塔预览、放置、升级、索敌与攻击；所有消费委托给 `GoldManager`。
- `ProjectilePool`：预热 24 个投射物，最多创建 48 个。
- `MonsterManager`：五种怪物共享 `BaseMonster`，负责对象池、贴地移动、绕障与攻城。
- `WaveManager`：十波配置、0.5 秒分批生成、解锁节奏、完成奖励和状态流转。
- `GridManager`：九宫格状态、随机安全格、预警闪烁、射线上升动画与玩家死亡判定。
- `UIManager`：管理世界固定面板、常驻 HUD、视线/距离越界复位、障碍避让及面板业务动作。
- `GameManager`：唯一状态转换总入口，协调波次、怪物、塔、射线的启停，处理失败/胜利结算及安全暂停。
- `SaveManager`：通过带 SHA-256 校验和的 `SharedPreferences` 保存晶核、养成等级和历史纪录。
- `MainViewModel`：向 UI 暴露金币、城墙、怪物和波次状态，以及波次和城墙操作。

## 空间 UI

- 主面板首次在视线正前方 1 米、视线高度定位，随后固定在世界空间；水平夹角超过 90° 或距离偏差超过 0.8 米时，0.3 秒平滑复位。
- 每次定位或复位都会查询 `SpatialManager` 障碍物，按距离回退并尝试左右偏移。
- HUD 位于视野下方并以低通插值轻微跟随；主面板按游戏状态显示校准、商店、塔升级或结算内容。
- 面板使用 PICO `Material.Thick/Regular` 玻璃材质、语义色和内建 `Button`/`LinearProgressIndicator`；单 AttachmentPanel 内以 10/20/30/40 的稳定合成层级表达底板、装饰、按钮和文字，避免实体级深度层引发闪烁。
- 内建 SpatialUI 按钮支持手部射线/捏合、直接触碰及手柄射线/扳机，并统一提供空间悬停和点击反馈；焦点持续 2 秒会执行眼动驻留确认。
- 主界面、结算和永久养成面板已串联；重新开始会清空局内状态并回到准备阶段，返回主界面会隐藏战斗场景。

## 总控、结算与存档

- 状态转换只允许由 `GameManager` 调用 `GameStateManager.transition`，其他模块通过事件或总控方法请求变化。
- 城墙归零和玩家触碰射线统一进入失败结算；第 10 波完成进入胜利结算。结算记录胜负、波数、总击杀、本局获得金币及 `波数 × 10` 晶核。
- 结算只提交一次，晶核及最高波数/击杀随后同步持久化；存档字段越界或校验失败会自动恢复安全默认值。
- 后台/来电会保留显式暂停，需玩家确认继续；HMD 跟踪丢失和离开主地面活动边界会冻结生成、怪物、投射物和射线，条件恢复后自动继续。
- 永久养成等级最高 100 级；升级费用为 `当前等级 × 50` 晶核。

## 城墙与经济规则

- 金币初始值为 300；击杀奖励取怪物配置，每波完成奖励 50。
- 塔购买、塔升级、城墙升级和修复都通过 `GoldManager.costGold` 原子扣费，余额不足不会产生负数。
- `GoldManager.gold`、`WallManager.wallState` 均为 `StateFlow`；变化同时发布类型化全局事件。
- 城墙初始为 100/100、1 级。上限升级费用为 `100 × 当前等级`，每级增加 50 最大生命，最高 5 级。
- 修复仅在 `WAVE_PAUSE` 可用，花费 80，按需求文字恢复“当前生命值的 50%”（向上取整，且不超过上限）。
- `WallManager.takeDamage` 会更新 UI 状态并触发短暂红色自发光；生命归零发布 `WallBrokenEvent`，并通过合法状态机进入失败结算。

## 怪物、波次和九宫格

- 五种怪物从最大 40 个实体的对象池复用，移动和攻城期间每帧将 Y 锁定到地面。
- 第 1 波 5 只普通虫；以后每波数量 +3、血量 ×1.2、速度 ×1.05、射线间隔 -0.3 秒。
- 第 3 波解锁疾行怪，第 5 波解锁重甲怪和精英怪，第 10 波保证生成 Boss。
- 本波全部击杀后回收怪物、奖励 50 金币并进入 `WAVE_PAUSE`。
- 战斗阶段先预警 1 秒，危险格射线在 0.3 秒内升至 2 米；仅按 HMD 的水平 X/Z 判断玩家是否安全。

## 运行时接口

```kotlin
viewModel.startFirstWave()
viewModel.startNextWave()
viewModel.upgradeWallMaxHp()
viewModel.repairWall()
```

`WaveManager.runtimeState` 提供波次进度，`MonsterManager.monsters` 提供怪物状态，`WallManager.wallState` 提供城墙状态，`GoldManager.gold` 提供统一金币状态。

## 构建与运行

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
pico-cli app install app\build\outputs\apk\debug\app-debug.apk
pico-cli app launch com.picoxr.mrspacetowerdefense --activity .ui.MainActivity
```

完整 MR 平面、控制器和怪物行为验证需要 PICO OS 6 设备，或已配置空间场景输入的 PICO 模拟器。
