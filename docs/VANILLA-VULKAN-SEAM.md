# The vanilla Vulkan seam — javap recon of 26.2's `com.mojang.blaze3d.vulkan`

Every claim below was read out of the real jar
(`attack-of-the-bteam-1.26.2/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-043a8b3edf/26.2/…`)
with `javap -p [-c]` and constant-pool `strings` scans, 2026-08-09. Nothing
here is remembered from tutorials. Where a question is open it says so.

## Q1 — Backend selection, and the option the popup button writes

**Answer.** The persistent option is `Options.preferredGraphicsBackend`, an
`OptionInstance<net.minecraft.client.PreferredGraphicsApi>` (options.txt
key `preferredGraphicsBackend`). The enum is `DEFAULT / OPENGL / VULKAN`
(`StringRepresentable`, serialized `"default"/"opengl"/"vulkan"`, captions
`options.graphicsApi.*`). Selection happens through
`PreferredGraphicsApi.getBackendsToTry() → GpuBackend[]`, whose bytecode
reads: **VULKAN → `[VulkanBackend, GlBackend]`; everything else — DEFAULT
and OPENGL alike — `[GlBackend, …]`.**

**THE FINDING THAT SHAPES THE MOD: `DEFAULT` is OpenGL-first.** Vulkan in
26.2 is opt-in. Nearly every player who installs Meshelium will be on the GL
backend until they flip the option — the owner's popup-with-a-button
requirement is the mod's front door, not an edge case.

Supporting facts:
- `Options.preferredGraphicsBackendFromStartup` (private field) records
  what was active at boot — the option is only read at startup, so the
  button's flow is *set option → save → prompt restart*. In code the
  button is `minecraft.options.preferredGraphicsBackend().set(VULKAN)`
  then `options.save()` — vanilla's own plumbing, no file surgery.
- Dev/harness forcing: `net.minecraft.client.main.Main` accepts a
  **`--graphicsBackend`** launch argument (constant-pool: `graphicsBackend`,
  `graphicsBackendOption`) and **`--vulkanValidation`** (validation
  layers!). **VERIFIED (wave 1):** the value converter is an anonymous
  `joptsimple.util.EnumConverter<PreferredGraphicsApi>` (`Main$1`), and
  `EnumConverter.convert` matches **enum names case-insensitively**
  (`equalsIgnoreCase` in the bytecode of both cached jopt-simple versions,
  5.0.4 and 6.0-alpha-3) — so `--graphicsBackend opengl|vulkan|default`
  (any case) is accepted.
- Failure semantics: `BackendCreationException$Reason` enumerates
  `VULKAN_LOADER_MISSING, VULKAN_INSTANCE_CREATION_FAILED, VULKAN_NO_DEVICE,
  VULKAN_DEVICE_VERSION_TOO_LOW, VULKAN_NO_GRAPHICS_QUEUE,
  VULKAN_MISSING_EXTENSION, VULKAN_MISSING_FEATURE, GLFW_ERROR,
  OPENGL_MISSING, OTHER` — `Minecraft` references the exception (constant
  pool), consistent with try-in-order fallback. So a machine that *can't*
  do Vulkan falls back to GL even with the option set: our popup's
  "enable" path is safe-by-default, and our gate must additionally handle
  "Vulkan active but no mesh shaders" itself.
- A datafixer `OptionsForceDefaultGraphicsApiFix` exists — Mojang has
  force-reset this option across a version already; don't be surprised if
  a future version resets players back to `default`.

**Consequence for Meshelium.** Wave 1's gate: on GL → dormant + one-time
popup with `[Enable Vulkan]` (set+save+restart prompt); on Vulkan without
`VK_EXT_mesh_shader` → dormant + a different honest message; on Vulkan
with it → active. The client gametest forces each path with
`--graphicsBackend`.

## Q2 — Device creation: where the extension gets enabled

**Answer.** One call site: **`VulkanBackend`** (its constant pool is the
only one in the package containing `vkCreateDevice`). The public entry is
`createDevice(long, ShaderSource, GpuDebugOptions, Runnable)`; the actual
creation is

```
private static VkDevice createDevice(
        Collection<String> extensions,
        VulkanPhysicalDevice physicalDevice,
        Set<VulkanFeature> features) throws BackendCreationException
```

— extension names and feature set arrive **as parameters**, which makes
this the cleanest possible mixin target: wrap/modify the two arguments,
appending `VK_EXT_mesh_shader` and a mesh-shader `VulkanFeature`, only
when the physical device supports them (never unconditionally — an
unsupported required extension would kill the whole Vulkan boot that
vanilla could otherwise complete).

Mojang shipped the utilities our injection needs, as public API in
`com.mojang.blaze3d.vulkan.init`:
- `VulkanPNextStruct(int sType, int structSize)` with
  `findOrCreateStructInPNextChain(...)` — pNext chain surgery, provided.
- `VulkanFeature(VulkanPNextStruct, String name, long offset)` with
  `get/set(VkPhysicalDeviceFeatures2, boolean)` — a feature bit inside a
  chained struct, provided.
- Worked examples sit right there as public statics on `VulkanBackend`:
  `MULTI_DRAW_FEATURES_STRUCT` + `MULTI_DRAW_FEATURE`
  (`VK_EXT_multi_draw` is vanilla's own optional device extension), plus
  `VK10/VK11/VK12_FEATURES_STRUCT`, `SYNC2_FEATURES_STRUCT`,
  `DYNAMIC_RENDERING_FEATURES_STRUCT`.
- Support probing exists too: `isFeatureSupported(VkPhysicalDevice,
  VulkanFeature)` (private static — accessor or reimplement; it is small).
- The vendor-conditional pattern is already vanilla practice: the
  `vulkan/checkpoints/` package enables NVIDIA- and AMD-specific debug
  extensions conditionally (`NvidiaCheckpointExtension`,
  `AmdCheckpointExtension`, `NoopCheckpointExtension` fallback).

So Meshelium's day-one injection is:
`MESH_SHADER_FEATURES_STRUCT = new VulkanPNextStruct(
VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT,
VkPhysicalDeviceMeshShaderFeaturesEXT.SIZEOF)` +
`new VulkanFeature(…, "meshShader", offset)` (+ `"taskShader"`), appended
alongside `"VK_EXT_mesh_shader"` at the `createDevice` boundary iff
supported. `REQUIRED_DEVICE_EXTENSIONS` / `REQUIRED_DEVICE_FEATURES`
(public static Sets) must stay untouched.

## Q3 — Handle reachability: everything is public

`VulkanDevice` exposes, **public, no access wideners needed**:
`vkDevice()`, `instance()`, `graphicsQueue()/computeQueue()/transferQueue()`
(→ `VulkanQueue`), **`vma()`** (vanilla allocates through the Vulkan
Memory Allocator and hands out the allocator handle), `createBuffer`,
`createTexture(View)`, `createCommandEncoder()` → `VulkanCommandEncoder`,
`getDeviceInfo()`. The constructor receives `Set<String>` of enabled
extensions — worth an accessor to double-check our extension landed.
`isIntegratedIntelMoltenVK` (private field) shows per-platform workaround
culture; `HintsAndWorkarounds` exists in `systems/`.

**Consequence:** the caps probe and all buffer/queue work ride public
API. Our allocations can go through vanilla's own VMA handle.

## Q4 — Frame structure & terrain draw path — **NOT YET INVESTIGATED**

Known so far: `systems/` has the abstraction (`RenderPass(Backend)`,
`RenderPassDescriptor$Attachment`, `CommandEncoder(Backend)`,
`GpuSurface$PresentMode`, `TransientMemory`/`VulkanTransientMemory`,
`GpuQuery(Pool)`, `TimerQuery`, `TracyGpuProfiler`); `VulkanRenderPass`,
`VulkanCommandEncoder`, `VulkanCommandPool`, `VulkanQueue$Submission`
(+`SemaphoreOp`, `SubmitStage`) exist. Where 26.2 dispatches terrain
section draws (the SectionRenderDispatcher equivalent) and where a mod
injects its own command-buffer work per frame is the wave-2 recon —
together with `net/minecraft/client/renderer/` census. Blocks nothing in
wave 1.

## Q5 — Shader pipeline: runtime GLSL→SPIR-V, but two stages only

`com.mojang.blaze3d.vulkan.glsl.GlslCompiler` compiles at runtime
(`createIntermediary(String, String, ShaderType)` →
`IntermediaryShaderModule`; reflection via `SpvcUtil` = SPIRV-Cross;
`ShaderCompileException`). Therefore **shaderc + SPIRV-Cross natives ship
with the game and are on our classpath**. But
`com.mojang.blaze3d.shaders.ShaderType = {VERTEX, FRAGMENT}` — vanilla's
compiler path cannot express task/mesh stages, and `VulkanRenderPipeline`
is built for its two-stage `RenderPipeline` abstraction.

**Consequence:** Meshelium calls shaderc directly through LWJGL
(`shaderc_task_shader` / `shaderc_mesh_shader` kinds) and creates its own
`VkShaderModule`s + mesh pipeline — while still riding vanilla's device,
VMA, queues, and (probably) command encoder. No build-time shader
toolchain. ⚠ UNVERIFIED: the shipped shaderc natives' version supports
mesh stages (added 2022; a 26.2-era LWJGL almost certainly carries it) —
the wave-1 caps probe logs `shaderc_get_spv_version` + a trial task-stage
compile to settle it on real hardware.

## Q6 — API level & pipeline style

`VulkanInstance` requests **`VK12.VK_API_VERSION_1_2`** (bytecode:
`getstatic VK12.VK_API_VERSION_1_2` → `VkApplicationInfo.apiVersion`).
Sync2 and **dynamic rendering** arrive as feature structs
(`SYNC2_FEATURES_STRUCT`, `DYNAMIC_RENDERING_FEATURES_STRUCT` on
`VulkanBackend`) — extension-based on a 1.2 core, no legacy render
passes. `VK_EXT_mesh_shader` needs ≥1.1 → compatible.
**Consequence:** our mesh pipeline is created with
`VkPipelineRenderingCreateInfo` (dynamic rendering), matching however
`VulkanRenderPass` drives attachments — confirm attachment formats when
wave 2 reads the frame structure.

## Mixin shopping list (wave 1)

| # | Target | Kind | Purpose |
|---|---|---|---|
| 1 | `VulkanBackend.createDevice(Collection, VulkanPhysicalDevice, Set)` (private static) | **`@Inject` at HEAD, mutate the arg collections in place** (what wave 1 shipped — see below) | append `VK_EXT_mesh_shader` + mesh/task `VulkanFeature`s, iff supported |
| 2 | `VulkanBackend.isFeatureSupported` (private static) | reimplemented (~10 lines, bytecode-matched) in `MeshShaderDeviceSupport` | support probe for the gate |
| 3 | `VulkanDevice` ctor's `Set<String>` (private) | NOT NEEDED — `MesheliumVulkanState` records the append at the seam itself | assert our extension actually enabled |

Row 1 refinement (wave 1, bytecode-verified): the private `createDevice`
has exactly **one call site** — the public overload — which passes **fresh
mutable copies** (`new HashSet<>(REQUIRED_DEVICE_EXTENSIONS)`, `new
ObjectOpenHashSet<>(REQUIRED_DEVICE_FEATURES)`) that vanilla itself then
mutates to add `VK_KHR_portability_subset`, checkpoint extensions and
`VK_EXT_multi_draw` before calling down. So plain `@Inject`-and-mutate is
sound and avoids `@ModifyVariable` arg-capture entirely.

Everything else wave 1 needs (option write, backend detect, caps query)
is public API. Popup: plain `Screen` pushed at title; "don't show again"
in Meshelium's own config.

## Wave-1 findings that later waves will want

- **`RenderSystem.getDevice()` returns the `GpuDevice` WRAPPER, not the
  backend.** 26.2's `GpuDevice` is a concrete class holding a private
  `GpuDeviceBackend` (`new GpuDevice(new VulkanDevice(...), ...)`) with no
  backend getter — `instanceof VulkanDevice` on it can never work. Backend
  detection goes through `getDeviceInfo().backendName()`, which is
  hardcoded `"Vulkan"` in `VulkanDevice`'s ctor and `"OpenGL"` on the GL
  side (also `GpuBackend.getName()`, same strings). `RenderSystem` also has
  a null-safe `tryGetDevice()`.
- **The current screen moved to `Gui`:** `Minecraft` no longer has
  `screen`/`setScreen` — use `minecraft.gui.screen()` /
  `minecraft.gui.setScreen(Screen)` (public field `Minecraft.gui`; the only
  Minecraft-level method is `setScreenAndShow`, which also forces a frame).
  The loading overlay is `minecraft.gui.overlay()`.
- **Client entrypoint runs BEFORE device creation — bytecode proof:**
  fabric-loader's `EntrypointPatch` (1.19.4+ rule) injects the client
  entrypoint invocation immediately before the `Thread.currentThread()`
  call in `Minecraft.<init>`, which sits at offset 563 in 26.2 — before
  `new Options(...)` (579), `RenderSystem.initBackendSystem()` (760), and
  `GpuBackend.createDevice(...)` (~1130) in the same constructor. Any
  boot-time decision must therefore be deferred; Meshelium's gate decides on
  the first client tick where the overlay is gone and the title screen is
  up.
- **Screens render via `GuiGraphicsExtractor` in 26.2** (`Screen` has
  `extractRenderState`/`extractBackground`, no `render(GuiGraphics,...)`).
  Building popups purely from stock widgets + `LinearLayout`/`FrameLayout`
  (the `ConfirmScreen` init pattern survives unchanged) avoids that surface
  entirely.
- **Client gametest facts:** the fabric runner starts tests on the first
  tick with `gui.overlay() == null` — it does NOT wait for `TitleScreen`,
  so a mod screen replacing the title cannot deadlock the harness; but
  every test MUST end with `gui.screen() instanceof TitleScreen`. Loom
  wipes `build/run/clientGameTest` (deleteGameTestRunDir) before each run,
  so per-run state (options.txt, config/meshelium.json) never leaks between
  harness invocations. `ClientGameTestContext.clickScreenButton(key)`
  presses a button by its translation key.

## UNVERIFIED ledger

1. `--graphicsBackend` accepted values (assumed `default|opengl|vulkan`).
2. shaderc mesh-stage support in the shipped natives (probe logs it).
3. `Minecraft`'s exact catch/fallback flow for `BackendCreationException`
   (Reason values + try-order make the shape clear; read before writing
   user-facing failure text).
4. All of Q4.
