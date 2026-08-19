# Project guidance: MR空间塔防：九宫格坚守

## Current scope

This is a PICO Spatial SDK 0.13.3 Android/Kotlin Stage application. It currently
provides the runnable shell for a 3x3 mixed-reality tower-defense game: permission
handling, Full Space Stage startup, plane-tracking lifecycle, a guarded global
game-state machine, validated game models, a typed Flow event bus, and MVVM package
  boundaries, calibrated ECS defense layout, tower placement/upgrades, pooled
  projectile combat, seven pooled monster archetypes, siege AI, ten-wave progression,
  a randomized HMD-tracked 3x3 energy-ray safety grid, centralized gold economy,
  a grounded 0.4 m upgradable wall with nine automatic weapon mounts and damage feedback,
  and continuously HMD-following SpatialUI
  HUD/main-menu/calibration/shop/tower-upgrade/settlement/permanent-growth panels,
  a mandatory post-calibration PREPARE shop/placement phase, a centralized session
  orchestrator, and checksummed local progression saves.

## Architecture

- `platform/SpatialApplication.kt` installs the process exception handler,
  initializes application-scoped managers, and calls `launch(::mainApp)`.
- Launcher branding uses the generated 3x3 fortress emblem in
  `drawable/ic_launcher_foreground.png` over the adaptive-icon navy background;
  `AndroidManifest.xml` points to `ic_launcher`/`ic_launcher_round`. Do not
  restore the scaffold `icon.3d.list` metadata unless matching 3D layers and SDF
  masks are authored, because it overrides the current icon on PICO launchers.
- `Main.kt` owns only the default Stage and `PicoTheme` root.
- `ui/MainActivity.kt` is a custom PICO spatial `ComponentActivity`; it must call
  `SpatialActivityDelegate.setSpatialContent()` and must not call `setContentView`.
- `manager/SpatialManager.kt` is the only owner of `PlaneTrackingManager`.
  It stores only `applicationContext`, marshals SDK callbacks to `Dispatchers.Main`,
  selects the primary floor, exposes ground/obstacle queries, and releases
  subscriptions/resources during Activity teardown.
- `manager/SceneLayoutManager.kt` owns calibrated layout geometry, nine center-out
  wall-top weapon mount points, and the logical spawn boundary under one disposable
  ECS root. Every new-game calibration clears the prior layout and waits for both a
  detected floor and the player's explicit calibration-panel confirmation. The next fresh
  HMD pose places the board at that floor height and rotates it to the confirmation-time
  flattened forward direction. That pose is committed exactly
  once: after calibration, HMD walking/turning, plane refinements and tracking-origin
  changes never rebuild, translate or rotate the battlefield. Only a new-game explicit
  calibration may choose a new world pose;
  `SpatialManager` continues updating query snapshots independently. Moving combat
  entities use the committed layout height plus the shared anti-clipping clearance,
  never a later live plane height. The obsolete ground placement-zone visual is hidden.
  The complete 3x3 grid remains behind the player-facing wall surface with an explicit
  0.08 m clearance; grid cells must never overlap the wall's 0.18 m depth.
- `manager/WallManager.kt` is the sole wall-state owner. It creates the grounded
  wall ECS visual plus nine shared-resource luminous slot pads, handles
  damage/flash/break events, max-HP/damage-reduction/reflection/regeneration
  upgrades, and pause-only repair.
- `manager/GoldManager.kt` is the sole gold ledger. All rewards and purchases must
  use it so non-negative validation and `GoldChangedEvent` publication stay atomic.
- `manager/GridManager.kt` owns the nine grid floor/beam visuals, interval-driven
  beam-down movement window, one-second warning, rise/active phases, random non-repeating
  safe-cell selection, and 10 Hz HMD-helmet-to-rendered-beam collision checks. Each
  cell is exactly 0.8 m square, so the complete activity area is 2.4 m by 2.4 m. Each retracted window begins
  with one second of flashing 8-red/1-green guidance, then keeps those colors steady
  until the configured wave interval elapses and the red beams rise. `GameManager`
  treats the complete 2.4 m square as the player bounds. Leaving the green cell is not
  lethal by itself; death occurs only when the 0.12 m HMD helmet horizontal circle intersects
  one of the eight actually rendered 0.50 m diameter by 2.0 m red beam cylinders. Beam
  contact intentionally ignores tracking-origin Y so a floor-height offset cannot suppress
  a valid hit. A rendered red-beam contact is always lethal from the first active hazard;
  permanent death-shield charges do not suppress `PlayerDiedEvent`. All nine visuals are children
  of one calibrated grid root and use exact 0.8 m local row/column offsets. Wave
  completion is gated until the first red-beam set has finished its full one-second
  visible phase, so fast-cleared early waves cannot pause the grid before any beam appears.
- `GridSystem.kt` samples the fresh HMD pose every ECS frame, converts it into
  scene-root coordinates, and advances grid timing plus helmet-radius collision checks.
- `manager/TowerManager.kt` owns placement preview, six immutable wall-mounted tower
  types, level-3/5 traits, frost/burn/totem support effects, target registration, and
  combat state; it delegates spending to `GoldManager`.
  Shop purchases atomically select the next free wall slot, deduct gold only after entity
  creation succeeds, and place the tower root at wall-top Y. All nine mounts are evenly
  spaced on the wall's rotated width axis; tower roots inherit wall yaw, while turrets use
  level yaw-only targeting so weapon models never appear diagonally mounted. Shared primitive resources are
  prewarmed during scene attachment so purchase clicks do not compile meshes on demand.
  Every active tower, including frost and burn, launches a visible pooled projectile;
  only the passive strength totem skips projectile creation.
  `TowerEntityNaming.kt` converts UUID hyphens to underscores because PICO entity names only
  accept ASCII letters, digits, and underscores.
- `manager/WallWeaponSlotRules.kt` owns the allocation-free nine-slot occupancy policy.
- `manager/TowerActionRules.kt` is the phase gate: PREPARE and WAVE_PAUSE allow placement,
  only WAVE_PAUSE allows upgrades, and FIGHTING alone runs automatic targeting/fire.
- `manager/TowerCombatSystem.kt` is the per-frame ECS bridge; `ProjectilePool.kt`
  reuses up to 48 projectile entities. Monster systems integrate by implementing
  `model/MonsterCombatTarget.kt` and registering targets with `TowerManager`.
- `manager/MonsterManager.kt` owns `BaseMonster` instances, a 40-slot pool with a
  25-active cap, grounded movement/obstacle steering, self-destruct/ranged/status
  behavior, and tower-target registration. `MonsterModelLibrary` loads the seven direct
  GLB assets once with `Entity.loadSuspend` during `SpatialView.initial`, keeps hidden
  templates alive for the Stage lifetime, and supplies recursive material-sharing clones
  to the pool. Every imported root uses scale `0.001` (the existing `0.1` combat scale
  plus the requested additional 100x downscale); a per-type procedural visual is retained
  only as a logged load-failure fallback. Runtime GLBs retain their original mesh,
  material binding and animation structure, but their fourteen embedded PNG textures are
  capped at 1024x1024 by `tools/optimize-monster-glb-textures.ps1`; recoverable 4096x4096
  sources live outside APK assets under `.spatialsdk/source-assets/monsters-4k/`. GLB
  assets are stored uncompressed in the APK.
  Siege damage
  is applied through `WallManager.takeDamage`. Re-entrant wave/settlement cleanup is
  gated until the active ECS map iteration exits; never clear `activeMonsters` from
  inside its frame iterator. The logical spawn boundary stays exactly 5 m in front of
  the wall even while a detected plane is still short. Normal monsters therefore move
  at 0.5 m/s (roughly 10 s over the route), with distinct catalog-relative speeds for
  other archetypes. Spawn points sample a pseudo-random two-dimensional band across the
  boundary with preferred 0.30 m center clearance and a collision-safe crowded-band
  fallback, so candidate exhaustion cannot starve the wave. Each monster keeps a
  corresponding target inside the middle 60% of the wall, which gently converges the two
  sides without collapsing every path into one line. Combat movement deliberately ignores
  detected furniture, walls and other monster bodies: every attacker advances directly to
  its assigned wall lane, so room geometry can never freeze a wave. Melee monsters stop at
  the wall surface instead of leaving a 0.3 m visual gap. Only the ranged acid monster keeps
  its configured 2 m standoff and fires a visible 3 m/s pooled acid shell; wall damage is
  applied on shell impact rather than immediately when its attack timer expires.
- `manager/WaveManager.kt` owns the ten-wave spawn coroutine, 800 ms per-monster interval,
  unlock progression, wave rewards, completion events, and legal game-state transitions.
- `manager/CampaignBalanceRules.kt` verifies campaign affordability. The original
  50-gold wave reward is intentionally retained to avoid an overflowing economy; it does
  not automatically fund nine level-five towers plus a level-five wall before wave 10.
  A no-splash simulation remains as a combat-only regression for a hypothetical max roster.
- `manager/WaveCatalog.kt` linearly schedules grid refresh from 6.000 s on wave 1 to
  3.000 s on wave 10 (about 0.333 s faster per wave); `GridRules` keeps warning at 1 s.
- `manager/UIManager.kt` owns two independent panel-placement policies. The shared
  single-modal AttachmentPanel (main menu/calibration/shop/tower upgrade/safety pause/
  settlement/growth) uses the HUD's 0.1 s exponential damping response, settling exactly
  after 0.4 s at the latest HMD-front pose whenever it opens, then locks in world space;
  it uses the same flight whenever its horizontal viewing angle exceeds 45 degrees or
  its horizontal distance drifts by more than 0.8 m. The combat
  HUD independently follows HMD translation, yaw and pitch on an exact 1.2 m head-centered
  sphere, targeting 15 degrees below gaze with a 0.1 s angular time constant. Smooth yaw/
  pitch first and then reconstruct the world pose; never Cartesian-lerp HUD positions or
  apply obstacle retreat, because both can shrink the HUD toward the face. Downward pitch
  is floor-clamped while preserving radial distance. Both AttachmentPanels are disabled
  before scene insertion, then enabled only through one atomic visibility result, so main
  menu and HUD are mutually exclusive from the first rendered frame. Main menu, tower
  upgrade, settlement, and permanent growth are explicitly classified under the same
  world-lock/recenter policy; upgrade and settlement modals overlay the still-visible HUD.
  Both paths use +Z-front facing and raw HMD world poses. Modal obstacle avoidance stays
  exactly on the HMD centreline and may retreat by at most 0.2 m; it must never search
  sideways or move inside the 0.75 m comfort radius. Large-angle modal recentering follows a
  circular horizontal arc with a 0.75 m hard comfort radius instead of Cartesian-lerping a
  chord through the player's face. AttachmentPanels are
  mounted directly in `SpatialView`. Before the first asynchronous HMD frame they use
  a safe ground + 1.6 m placeholder pose instead of remaining at the SDK origin.
- `manager/UIFollowSystem.kt` calls `UIManager.update(deltaTime)` on every Spatial ECS
  frame; never move modal threshold checks or HUD following back into the `SpatialView`
  Compose update callback because that callback only runs for UI updates, not every
  render frame.
- `manager/GameManager.kt` is the only caller of `GameStateManager.transition`.
  It coordinates module entry/exit, event-driven win/failure settlement, run reset,
  scene visibility, and background/tracking/bounds pause reasons. Settlement native
  resource destruction yields once so an originating ECS frame can unwind safely.
  App-background, tracking-loss, and OUT_OF_BOUNDS reasons all freeze monster movement,
  wave spawning, tower fire, wall regeneration, and grid hazards through one predicate.
  Returning anywhere inside the complete 3x3 grid clears OUT_OF_BOUNDS automatically
  and resumes the same wave without recycling its monsters.
  Calibration completion must stop in PREPARE; only the shop's explicit start-first-wave
  action may transition PREPARE to FIGHTING. `PREPARE` itself proves calibration completed,
  so restart from SETTLE reuses the committed board and opens SHOP directly; it must never
  route through a stale UI-only calibration flag to a disabled 100% calibration panel.
- `manager/SaveManager.kt`, `manager/PermanentGrowthCatalog.kt`, and
  `utils/LocalSaveStore.kt` own crystals, twelve independent level-10 permanent upgrades,
  runtime bonus snapshots and historical records with range repair and SHA-256 validation.
- `viewmodel/MainViewModel.kt` exposes wave, wall, gold, and monster state plus
  wave commands and wall upgrade/repair actions.
- `content/TowerPlacementInput.kt` remains as a compatibility controller path; any confirmed
  placement is routed to the same next-free wall-slot allocator, so it cannot exceed nine.
- `content/HomeStage.kt` attaches the Stage scene root, the single-modal AttachmentPanel
  and independent HUD AttachmentPanel, assigns modal-over-HUD UI sort bias, routes
  spatial tower taps to upgrades, and registers UI placement updates independently
  from the paused game-simulation governor.
- `ui/spatial/GamePanels.kt` implements calibration, shop, tower upgrade,
  settlement and persistent HUD with built-in PICO SpatialUI controls. In PREPARE the
  shop enables tower purchasing/placement, disables wall maintenance, and labels its
  primary action "开始第一波". Purchase buttons keep the modal visible and immediately
  report the assigned wall slot instead of hiding the shop to wait for a placement ray.
  `UIManager` yields once after a click so SpatialUI input dispatch completes before native
  ECS mutation, and an atomic in-flight guard rejects duplicate click re-entry.
- `manager/BaseManager.kt` owns Application-context initialization, manager scope,
  destruction, and lifecycle-bound page child scopes.
- `manager/PerformanceTuning.kt` owns the allocation-free monster LOD policy,
  10 Hz hazard cadence, the 30 Hz non-combat application-update governor, and
  a hard application-update stop while the Activity is backgrounded.
- Monster, projectile, grid-beam and tower render paths reuse pooled entities. Monster
  instances clone seven one-time GLB templates with shared immutable material instances;
  procedural fallback monsters, projectiles, grid beams and towers reuse a ref-counted
  gameplay mesh plus shared materials. Do not introduce per-entity immutable resources or
  collection/Pair allocations into ECS frame loops.
- `SceneLayoutManager` and `WallManager` explicitly close every native mesh/material
  after destroying their ECS roots during recalibration, settlement and Stage teardown.
- `manager/GameStateRules.kt` is the only legal-transition table; never mutate
  game state outside `GameStateManager.transition`.
- `event/EventBus.kt` is the application-wide typed `SharedFlow<GameEvent>` bus.
- `model/` contains immutable validated game configuration and runtime-state records.

## UI rule

All in-Stage 2D UI must use PICO SpatialUI under `PicoTheme`; do not add Material
or Material3. The only Android View layout is the ViewBinding-based permission
fallback dialog required by the product contract.

## Build and run

- Build: `./gradlew assembleDebug`
- Unit tests: `./gradlew testDebugUnitTest`
- Release verification: `./gradlew verifyTextureBudgets testDebugUnitTest assembleRelease`
- Install: `./gradlew installDebug`
- Launch: `adb shell am start -n com.picoxr.mrspacetowerdefense/.ui.MainActivity`

The official 0.13.3 scaffold requires API 35 and Kotlin 2.x metadata support.
Do not downgrade to the requested legacy API 33/Kotlin 1.9 values without also
selecting and validating a compatible older Spatial SDK.

Release signing is loaded only from the ignored root `keystore.properties` file.
See `docs/RELEASE_AND_DEVICE_VALIDATION.md`; never commit credentials or keystores.
The 90 fps / 500 MB targets require a physical PICO release-build trace and must
not be claimed from emulator behavior alone.

## Natural next increments

1. Add content-specific permanent growth bonuses to tower/wall base values.
2. Add automated device-side hand/controller/eye interaction journeys.
