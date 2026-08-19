# Release 打包与 PICO 真机验收

## 性能预算

- 目标刷新率：90 fps，即每帧总预算约 11.11 ms。
- 目标内存：应用总 PSS 小于 500 MB。
- Stage 场景预算参考：约 90 Draw Calls、约 35 万三角形、约 60 个活跃实体；最终以目标 PICO 设备的 Perfetto/系统指标为准。
- 这些是验收门槛，不是仅凭编译成功即可证明的结果。必须用 Release 包在目标设备连续运行至少 10 分钟后签字确认。

## 已落地优化

- 静态/重复渲染资源按类型共享：九宫格地板与射线、同类怪物、同类塔、同类投射物复用 Mesh/Material，由单一 ECS 根分组提交，避免每实体创建不可变资源。
- 怪物池上限 40、投射物池上限 48；受击闪烁与塔强化光效复用常驻材质/实体状态，不在命中热路径创建临时特效。
- 同屏怪物超过 20 只时，3 米外怪物按三帧条带更新并累计位移时间；状态快照降至 10 Hz，射线死亡检测固定 10 Hz。
- 非战斗应用更新降至 30 Hz；Activity 进入后台后，空间平面跟踪停止，SpatialView 输入/UI 更新及四个 ECS 游戏系统立即短路，回前台仍保持战斗暂停。
- 结算释放怪物池、投射物池、塔、网格、城墙战斗资源；重新校准、结算和 Stage 销毁均显式关闭 PICO Mesh/Material 原生资源。
- 玩法场景当前采用程序化几何，无运行时玩法贴图；后续贴图必须通过下述 ETC2/KTX2 构建门禁。

## Release 签名

工程不会提交密钥或口令。复制根目录的 `keystore.properties.example` 为
`keystore.properties`，填写本机绝对或项目根目录相对路径：

```properties
storeFile=signing/release.jks
storePassword=REPLACE_ME
keyAlias=mr-grid-defense
keyPassword=REPLACE_ME
```

如尚无正式密钥，可在安全位置创建：

```powershell
keytool -genkeypair -v -keystore signing\mr-grid-defense-release.jks `
  -alias mr-grid-defense -keyalg RSA -keysize 4096 -validity 10000
```

`keystore.properties` 和密钥文件不得进入版本库。缺少完整签名配置时，构建仍会生成
`app-release-unsigned.apk`，但它不是可发布的正式签名包。

## 构建命令

```powershell
.\gradlew.bat clean verifyTextureBudgets testDebugUnitTest assembleRelease
```

构建会自动执行：

- R8 代码压缩、优化与混淆；
- Android 资源收缩；
- ETC2/KTX2 纹理格式和尺寸门禁；
- Release lint vital 检查。

完整签名配置存在时，APK 位于：

```text
app/build/outputs/apk/release/app-release.apk
```

未配置签名时，APK 位于：

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

校验签名及 R8 产物：

```powershell
apksigner verify --verbose --print-certs app\build\outputs\apk\release\app-release.apk
Get-Item app\build\outputs\mapping\release\mapping.txt
```

发布时必须安全归档对应版本的 `mapping.txt`，否则线上混淆崩溃栈无法还原。

## 贴图准入规则

- 怪物、塔和场景贴图放入 `app/src/main/assets/textures/world/`，最大 1024×1024。
- UI 贴图放入 `app/src/main/assets/textures/ui/`，最大 512×512。
- 只接受装载 ETC2/EAC Vulkan 格式的 `.ktx2` 文件；构建任务会读取 KTX2 头部验证格式和尺寸。
- 当前战斗场景使用程序化 ECS 几何与共享材质，没有玩法贴图；未使用且内嵌 PNG 的脚手架 `box.usdz` 已从 APK 资源打包中排除。

## 十项真机验收

1. **空间校准**：冷启动、重新授权和跟踪恢复各测试一次；主地面选择正确，脚部高度兜底无突跳。
2. **贴地与穿模**：绕场观察城墙、九宫格、三种塔和五种怪物；底部贴地，无悬空、穿模或 Y 轴漂移。
3. **防御塔**：逐一验证购买限制、暂停期放置、攻击范围/攻速/伤害、炮台溅射、五级升级和发光反馈。
4. **怪物与波次**：跑完 10 波；数量、0.5 秒分批生成、解锁节奏、倍率、直线/避障移动和攻城伤害均符合配置。
5. **九宫格射线**：验证 1 秒预警、0.3 秒升起、非连续安全格、0.1 秒判定频率、水平位置死亡和暂停期冻结。
6. **空间面板**：验证 1 米/视线高定位、世界锁定、越界复位、障碍避让、HUD 轻跟随、无 Z-fighting/闪烁/乱飘。
7. **金币与城墙**：验证初始值、击杀/波次奖励、所有消费、余额不为负、升级/修复边界和 UI 同步。
8. **稳定性**：完整跑通主界面到胜/负结算并重开；检查 logcat 无 Java/Kotlin/native crash、ANR 或持续异常刷屏。
9. **持续性能**：使用正式签名 Release 包连续战斗至少 10 分钟；目标稳定 90 fps、帧预算约 11.11 ms、总 PSS <500 MB，无持续热降频或严重卡顿。记录设备型号、PICO OS、场景、构建号及 Perfetto/系统采样文件。
10. **存档**：完成结算后强杀进程并重启；晶核、养成等级、最高波数和最多击杀保持一致，篡改或越界数据可自动修复。

建议性能验收同时记录：平均/最低 FPS、90/95/99 分位帧时、CPU/GPU 频率与温度、Java/native/graphics PSS、Draw Call、三角形数和活跃实体峰值。模拟器只用于功能回归，不能替代真机性能结论。
