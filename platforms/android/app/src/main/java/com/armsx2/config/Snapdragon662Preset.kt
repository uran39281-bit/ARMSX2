package com.armsx2.config

/**
 * Black Ice performance preset for Snapdragon 662 / Adreno 610 devices.
 *
 * This is intentionally aggressive but avoids the two VU hacks documented as
 * known game-breakers (deferred writes and stall-simulation skipping). It is
 * opt-in from the Performance tab so users can immediately switch back to a
 * safer profile when a title needs more accuracy.
 */
fun Settings.snapdragon662Preset(): Settings = Settings.lowEndPreset(
    copy(
        // CPU: reduce EE load while keeping the normal recompilers/fastmem on.
        eeCycleRate = -2,          // 60% EE clock
        eeCycleSkip = 1,
        mtvu = true,               // SD662 has 8 CPU cores
        vu1Instant = true,
        vuFlagHack = true,
        intcStat = true,
        waitLoop = true,
        fastCDVD = true,
        recEE = true,
        recIOP = true,
        recVU0 = true,
        recVU1 = true,
        enableFastmem = true,
        vuNeonFusions = true,
        vuDeferredWrites = false,
        vuSkipStallSim = false,

        // GPU: Adreno 610 is usually the limiting side in heavier PS2 titles.
        upscaleFloat = 1.0f,
        hwScaler = 1,              // 1x PS2 output surface
        accurateBlendingUnit = 0,
        hwMipmap = false,
        textureFiltering = 0,
        displayBilinear = 0,
        texturePreloading = 1,
        hardwareDownloadMode = 3,  // Unsynchronized readbacks: fast, may glitch in some games
        hwRov = false,
        adrenoFbFetch = true,
        coalesceRenderPasses = true,
        gpuProfile = 2,            // Adreno
        dithering = 0,
        triFilter = 0,
        maxAnisotropy = 0,
        fxaa = false,
        shaderChainEnabled = false,
        casMode = 0,
        upscaler = Settings.UPSCALER_OFF,
        loadTextureReplacements = false,
        precacheTextureReplacements = false,
        vsyncEnable = false,
        vsyncQueueSize = 2,
        skipDuplicateFrames = true,

        // Audio/UI: free a little CPU without muting or changing game speed.
        spu2LightweightMix = true,
        audioBufferMs = 100,
        affinityMode = 0,
    ),
    mtvu = true,
)
