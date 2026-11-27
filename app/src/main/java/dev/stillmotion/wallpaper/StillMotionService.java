package dev.stillmotion.wallpaper;

import android.app.KeyguardManager;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceHolder;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

public class StillMotionService extends android.service.wallpaper.WallpaperService {
    private static final String TAG = "StillMotion";
    private static final long UNLOCK_RECHECK_MILLIS = 80;
    private enum StartBlocker {
        NONE, STATIC_BUILD, POLICY_ERROR, PREVIEW, ALREADY_PLAYED, API_BELOW_34,
        STATIC_PREFERENCE, LOCK_ONLY, NON_INTERACTIVE, POWER_SAVE, SECONDARY_DISPLAY,
        UNKNOWN_DESTINATION, HIDDEN, SURFACE_UNAVAILABLE, KEYGUARD, DISPLAY_NOT_ON,
        WAITING_FOR_POSTER, CANVAS_SOFTWARE, VIDEO_PACK_UNAVAILABLE, VIDEO_TEARDOWN;

        boolean mayResolveDuringUnlock() {
            switch (this) {
                case UNKNOWN_DESTINATION:
                case HIDDEN:
                case SURFACE_UNAVAILABLE:
                case KEYGUARD:
                case DISPLAY_NOT_ON:
                case WAITING_FOR_POSTER:
                    return true;
                default:
                    return false;
            }
        }
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<StillEngine> liveEngines = new LinkedHashSet<>();
    private boolean playedSinceScreenOff;
    private boolean screenOffSignalled;
    private boolean communityWakePlayed;
    private String communityLoopId;
    private final OrbitaMotion orbitaMotion = new OrbitaMotion();
    private boolean receiving;
    private int nextEngineId;

    protected boolean importedVideo() {
        return false;
    }

    private final BroadcastReceiver deviceEvents = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                screenOffSignalled = true;
                communityWakePlayed = false;
                communityLoopId = null;
                playedSinceScreenOff = false;
                orbitaMotion.screenOff();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                screenOffSignalled = false;
            }
            for (StillEngine engine : liveEngines) {
                engine.onDeviceEvent(action);
            }
        }
    };

    @Override
    public Engine onCreateEngine() {
        return new StillEngine(++nextEngineId);
    }

    private void attachDeviceEvents(StillEngine engine) {
        liveEngines.add(engine);
        if (receiving) return;
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        // All four actions are protected system broadcasts. Keep this filter system-only.
        try {
            registerReceiver(deviceEvents, filter);
            receiving = true;
        } catch (SecurityException unavailable) {
            if (BuildConfig.DEBUG) engine.log("receiver-refused");
        }
    }

    private void detachDeviceEvents(StillEngine engine) {
        liveEngines.remove(engine);
        if (receiving && liveEngines.isEmpty()) {
            unregisterReceiver(deviceEvents);
            receiving = false;
        }
    }

    // Engine callbacks, device events, preferences and frame posts run on the same looper.
    private final class StillEngine extends Engine {
        private final int id;
        private final SharedPreferences settings = Theme.preferences(StillMotionService.this);
        private final PowerManager power = getSystemService(PowerManager.class);
        private final KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        private final CanvasBackend backend = new CanvasBackend(getResources());
        private final UnlockWindow unlock = new UnlockWindow();
        private final Runnable unlockRecheck = this::tryDeferredUnlock;
        private final Runnable resizeRedraw = () -> {
            tryWakePlayback();
            presentPoster(false);
            tryDeferredUnlock();
        };
        private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged =
                (preferences, key) -> onSettingsChanged(key);
        private final boolean isVideo = importedVideo();
        private Theme theme = selectedTheme();
        private OrbitaArchive.Spec orbitaTheme = isVideo ? Orbita.find(StillMotionService.this, theme.id) : null;
        private CommunityArchive.Spec community = CommunityPack.spec(StillMotionService.this, theme.id);
        private SurfaceHolder holder;
        private boolean visible;
        private boolean needsPoster = true;
        private boolean destroyed;
        private int generation;
        private int surfaceWidth;
        private int surfaceHeight;
        private int surfaceFormat;
        private int policyState;
        private boolean policyEnabled = !BuildConfig.PROBE;
        private Playback active;
        private VideoClip video;
        private boolean communityAfterPending;
        private boolean communityLoopPlaying;
        private boolean communityWakeVideo;
        private boolean communityWakeFinished;
        private OrbitaRenderer orbita;
        private boolean orbitaFailed;
        private boolean orbitaMayAnimate;
        private boolean orbitaKeyguard;
        private boolean orbitaDrawingZoom;
        private int orbitaFlags;
        private final Runnable orbitaFrame = () -> renderOrbita(false, false);
        private final Runnable wakeRecheck = this::tryWakePlayback;

        StillEngine(int id) {
            this.id = id;
        }

        private Theme selectedTheme() {
            return isVideo ? Theme.selectedVideo(settings) : Theme.selected(settings);
        }

        private void onSettingsChanged(String key) {
            if (destroyed) return;
            if (Orbita.LIBRARY_KEY.equals(key) && (!isVideo || !Orbita.isOrbita(theme.id))) return;
            if ((Orbita.GAS_EFFECT_KEY.equals(key) || Orbita.AFTER_KEY.equals(key)) && orbitaTheme == null) return;
            orbitaFailed = false;
            main.removeCallbacks(wakeRecheck);
            Theme next = selectedTheme();
            if (!next.id.equals(theme.id) || Orbita.LIBRARY_KEY.equals(key)) {
                communityLoopId = null;
                theme = next;
                orbitaTheme = isVideo ? Orbita.find(StillMotionService.this, theme.id) : null;
                community = CommunityPack.spec(StillMotionService.this, theme.id);
                communityWakeFinished = false;
                needsPoster = true;
            }
            if (Orbita.GAS_EFFECT_KEY.equals(key)) needsPoster = true;
            if (Theme.STATIC_KEY.equals(key) && (orbitaTheme != null || community != null)) needsPoster = true;
            if (Orbita.AFTER_KEY.equals(key) && !settings.getBoolean(Orbita.AFTER_KEY, true)) {
                orbitaMotion.disableAfter(SystemClock.uptimeMillis());
                needsPoster = true;
            }
            cancel(true);
        }

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            setTouchEventsEnabled(false);
            setOffsetNotificationsEnabled(false);
            settings.registerOnSharedPreferenceChangeListener(settingsChanged);
            if (!isPreview()) attachDeviceEvents(this);
            if (BuildConfig.DEBUG) {
                log("attached preview=" + isPreview() + " policy="
                        + (BuildConfig.PROBE ? "probe" : NativePolicy.status())
                        + " flags=" + wallpaperFlags());
            }
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder surfaceHolder) {
            super.onSurfaceCreated(surfaceHolder);
            if (destroyed) return;
            main.removeCallbacks(resizeRedraw);
            generation++;
            holder = surfaceHolder;
            surfaceWidth = 0;
            surfaceHeight = 0;
            needsPoster = true;
            orbitaFailed = false;
            dispatch(NativePolicy.SURFACE_READY, false, false);
            backend.newSurface();
            log("surface-created");
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) {
            super.onSurfaceChanged(surfaceHolder, format, width, height);
            if (destroyed || surfaceHolder != holder) return;
            if (width == surfaceWidth && height == surfaceHeight && format == surfaceFormat) return;
            surfaceWidth = width;
            surfaceHeight = height;
            surfaceFormat = format;
            if (orbita != null) orbita.resize();
            if (active != null || video != null) cancel(false);
            needsPoster = true;
            if (BuildConfig.DEBUG) log("surface-size " + width + "x" + height);
            // AOSP normally sends a redraw in this same dispatch. Fall back if an OEM omits it.
            main.removeCallbacks(resizeRedraw);
            if (visible && power.isInteractive()) main.post(resizeRedraw);
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder surfaceHolder) {
            super.onSurfaceRedrawNeeded(surfaceHolder);
            if (surfaceHolder != holder || destroyed) return;
            main.removeCallbacks(resizeRedraw);
            if (active != null || video != null) return;
            log("system-redraw");
            boolean alreadyPlayed = playedSinceScreenOff;
            tryDeferredUnlock();
            if (alreadyPlayed || !playedSinceScreenOff) presentPoster(true);
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder surfaceHolder) {
            if (surfaceHolder == holder) {
                main.removeCallbacks(resizeRedraw);
                clearDeferredUnlock();
                dispatch(NativePolicy.SURFACE_LOST, false, false);
                stopPlayback(false);
                holder = null;
                generation++;
                needsPoster = true;
                backend.newSurface();
                log("surface-destroyed");
            }
            super.onSurfaceDestroyed(surfaceHolder);
        }

        @Override
        public void onVisibilityChanged(boolean nowVisible) {
            if (destroyed) return;
            visible = nowVisible;
            if (BuildConfig.DEBUG) log("visibility=" + nowVisible);
            dispatch(nowVisible ? NativePolicy.VISIBLE : NativePolicy.HIDDEN, false, false);
            if (!nowVisible) {
                main.removeCallbacks(resizeRedraw);
                main.removeCallbacks(wakeRecheck);
                clearDeferredUnlock();
                stopPlayback(false);
            } else if (power.isInteractive()) {
                tryDeferredUnlock();
                tryWakePlayback();
                presentPoster(false);
            }
        }

        @Override
        public void onWallpaperFlagsChanged(int which) {
            super.onWallpaperFlagsChanged(which);
            if (BuildConfig.DEBUG) log("wallpaper-flags=" + which);
            if (which != 0 && (which & WallpaperManager.FLAG_SYSTEM) == 0) cancel(true);
            else {
                tryDeferredUnlock();
                if (orbita != null) drawOrbita(false);
                else tryWakePlayback();
            }
        }

        @Override
        public void onDestroy() {
            if (destroyed) return;
            destroyed = true;
            main.removeCallbacks(resizeRedraw);
            main.removeCallbacks(wakeRecheck);
            clearDeferredUnlock();
            dispatch(NativePolicy.DESTROY, false, false);
            stopPlayback(false);
            holder = null;
            generation++;
            detachDeviceEvents(this);
            settings.unregisterOnSharedPreferenceChangeListener(settingsChanged);
            log("destroyed");
            super.onDestroy();
        }

        private void onDeviceEvent(String action) {
            if (destroyed || action == null) return;
            if (BuildConfig.DEBUG) {
                log("broadcast " + action + " interactive=" + power.isInteractive()
                        + " keyguard=" + keyguard.isKeyguardLocked() + " visible=" + visible);
            }
            switch (action) {
                case Intent.ACTION_SCREEN_OFF:
                    needsPoster = true;
                    communityWakeFinished = false;
                    main.removeCallbacks(wakeRecheck);
                    main.removeCallbacks(resizeRedraw);
                    clearDeferredUnlock();
                    dispatch(NativePolicy.SCREEN_OFF, false, false);
                    stopPlayback(false);
                    break;
                case Intent.ACTION_SCREEN_ON:
                    if (orbitaTheme != null || (community != null && community.wake)) {
                        needsPoster = true;
                        tryWakePlayback();
                        if (orbita == null && video == null && visible) {
                            main.removeCallbacks(wakeRecheck);
                            main.postDelayed(wakeRecheck, 80);
                        }
                    }
                    if (visible && power.isInteractive()) presentPoster(false);
                    tryDeferredUnlock();
                    break;
                case Intent.ACTION_USER_PRESENT:
                    onUserPresent();
                    break;
                case PowerManager.ACTION_POWER_SAVE_MODE_CHANGED:
                    if (power.isPowerSaveMode()) {
                        main.removeCallbacks(wakeRecheck);
                        cancel(true);
                    } else if (visible && (orbitaTheme != null || community != null)) {
                        needsPoster = true;
                        tryWakePlayback();
                        presentPoster(false);
                    }
                    break;
                default:
                    break;
            }
        }

        private int wallpaperFlags() {
            return Build.VERSION.SDK_INT >= 34 ? getWallpaperFlags() : 0;
        }

        private boolean orbitaCanAnimate() {
            if (Build.VERSION.SDK_INT < 34 || screenOffSignalled || !visible || !policyEnabled
                    || isPreview() || power.isPowerSaveMode()
                    || !power.isInteractive() || settings.getBoolean(Theme.STATIC_KEY, false)) return false;
            Display display = getDisplayContext().getDisplay();
            return display != null && display.getDisplayId() == Display.DEFAULT_DISPLAY
                    && display.getState() == Display.STATE_ON;
        }

        private void tryWakePlayback() {
            if (community != null) {
                if (!community.wake || communityWakePlayed || video != null || !orbitaCanAnimate()
                        || !keyguard.isKeyguardLocked()
                        || (wallpaperFlags() & WallpaperManager.FLAG_LOCK) == 0
                        || holder == null || !holder.getSurface().isValid()) return;
                communityWakePlayed = true;
                needsPoster = true;
                presentPoster(false);
                needsPoster = true;
                communityWakeVideo = true;
                video = new VideoClip(main, this::onVideoEnded);
                video.start(CommunityPack.clip(StillMotionService.this, theme.id), holder.getSurface());
                log("community-wake-start");
                return;
            }
            if (orbitaTheme == null || orbitaFailed || orbita != null) return;
            updateOrbitaState();
            if (orbitaMayAnimate && orbitaKeyguard && (orbitaFlags & WallpaperManager.FLAG_LOCK) != 0) {
                renderOrbita(false, false);
            }
        }

        private boolean drawOrbita(boolean forced) {
            return drawOrbita(forced, false);
        }

        private boolean drawOrbita(boolean forced, boolean beginZoom) {
            main.removeCallbacks(orbitaFrame);
            updateOrbitaState();
            return renderOrbita(forced, beginZoom);
        }

        private void updateOrbitaState() {
            orbitaMayAnimate = orbitaCanAnimate();
            orbitaKeyguard = !isPreview() && keyguard.isKeyguardLocked();
            orbitaFlags = wallpaperFlags();
        }

        private boolean renderOrbita(boolean forced, boolean beginZoom) {
            if (orbitaTheme == null || orbitaFailed || holder == null || !holder.getSurface().isValid()
                    || (!forced && !visible)) {
                closeOrbita();
                return false;
            }
            long now = SystemClock.uptimeMillis();
            boolean animate = orbitaMayAnimate && !screenOffSignalled;
            boolean waves = animate && orbitaKeyguard && (orbitaFlags & WallpaperManager.FLAG_LOCK) != 0;
            try {
                if (orbita == null) {
                    orbita = new OrbitaRenderer();
                    if (!orbita.open(holder.getSurface(), getResources(), orbitaTheme,
                            settings.getBoolean(Orbita.GAS_EFFECT_KEY, false))) {
                        throw new IOException("Could not open Orbita renderer");
                    }
                    now = SystemClock.uptimeMillis();
                }
                if (beginZoom) orbitaMotion.startZoom(now, settings.getBoolean(Orbita.AFTER_KEY, true));
                boolean home = animate && !orbitaKeyguard && (orbitaFlags & WallpaperManager.FLAG_SYSTEM) != 0;
                boolean zoom = home && orbitaMotion.zooming(now);
                boolean after = home && orbitaMotion.playingAfter(now);
                if (after) orbitaMotion.resumeAfter(now);
                boolean initial = !isPreview() && (orbitaKeyguard || orbitaMotion.waitingForUnlock());
                if (waves) orbitaMotion.startWake(now);
                float wake = initial || zoom ? orbitaMotion.wakeProgress(now) : 1f;
                float progress = initial || zoom ? orbitaMotion.zoomProgress(now) : 1f;
                float phase = isPreview() ? 0f : orbitaMotion.phase(now);
                float afterStrength = isPreview() ? 0f : orbitaMotion.afterStrength(now);
                if (!orbita.draw(wake, progress, phase, afterStrength)) {
                    throw new IOException("Could not present Orbita frame");
                }
                needsPoster = false;
                orbitaDrawingZoom = zoom || after;
                if (waves || zoom || after) {
                    main.postAtTime(orbitaFrame,
                            Math.max(now + (zoom ? 33 : after ? 67 : 42), SystemClock.uptimeMillis() + 4));
                } else {
                    closeOrbita();
                    if (progress >= 1f) dispatch(NativePolicy.FINISH, false, false);
                }
                return true;
            } catch (IOException | RuntimeException failure) {
                closeOrbita();
                orbitaFailed = true;
                if (beginZoom) orbitaMotion.startZoom(now);
                log("orbita-renderer-failed");
                presentPoster(forced);
                return false;
            }
        }

        private void closeOrbita() {
            main.removeCallbacks(orbitaFrame);
            orbitaDrawingZoom = false;
            if (orbita != null) {
                orbita.close();
                orbita = null;
            }
        }

        private StartBlocker startBlocker() {
            if (BuildConfig.PROBE) return StartBlocker.STATIC_BUILD;
            if (!policyEnabled) return StartBlocker.POLICY_ERROR;
            if (isPreview()) return StartBlocker.PREVIEW;
            if (playedSinceScreenOff) return StartBlocker.ALREADY_PLAYED;
            if (Build.VERSION.SDK_INT < 34) return StartBlocker.API_BELOW_34;
            if (settings.getBoolean(Theme.STATIC_KEY, false)) return StartBlocker.STATIC_PREFERENCE;
            if (screenOffSignalled || !power.isInteractive()) return StartBlocker.NON_INTERACTIVE;
            if (power.isPowerSaveMode()) return StartBlocker.POWER_SAVE;
            int flags = wallpaperFlags();
            if (flags == 0) return StartBlocker.UNKNOWN_DESTINATION;
            if ((flags & WallpaperManager.FLAG_SYSTEM) == 0) return StartBlocker.LOCK_ONLY;
            if (!visible) return StartBlocker.HIDDEN;
            if (holder == null || !holder.getSurface().isValid()) return StartBlocker.SURFACE_UNAVAILABLE;
            if (keyguard.isKeyguardLocked() || keyguard.isDeviceLocked()) return StartBlocker.KEYGUARD;
            Display display = getDisplayContext().getDisplay();
            if (display == null || display.getState() != Display.STATE_ON) return StartBlocker.DISPLAY_NOT_ON;
            if (display.getDisplayId() != Display.DEFAULT_DISPLAY) return StartBlocker.SECONDARY_DISPLAY;
            if (isVideo) {
                if (community != null && !community.wake) {
                    return video == null ? StartBlocker.NONE : StartBlocker.VIDEO_TEARDOWN;
                }
                if (CommunityPack.isCustom(theme.id)) return StartBlocker.VIDEO_PACK_UNAVAILABLE;
                if (video != null) return StartBlocker.VIDEO_TEARDOWN;
                return (orbitaTheme != null
                        || FlymePack.contains(StillMotionService.this, theme.id))
                        ? StartBlocker.NONE : StartBlocker.VIDEO_PACK_UNAVAILABLE;
            }
            if (backend.hasSoftwareProducer()) return StartBlocker.CANVAS_SOFTWARE;
            return backend.hasHardwareProducer() ? StartBlocker.NONE : StartBlocker.WAITING_FOR_POSTER;
        }

        private void onUserPresent() {
            main.removeCallbacks(wakeRecheck);
            if (community != null && community.wake) {
                if (communityWakeVideo && video != null) video.cancel();
                else if (visible) presentPoster(true);
                return;
            }
            if (orbita != null) updateOrbitaState();
            if (unlock.armed()) {
                tryDeferredUnlock();
                return;
            }
            StartBlocker blocker = startBlocker();
            if (BuildConfig.DEBUG) log("unlock-check=" + blocker + " flags=" + wallpaperFlags());
            if (blocker.mayResolveDuringUnlock()) {
                dispatch(NativePolicy.DEFER_USER_PRESENT, false, false);
            } else {
                dispatch(NativePolicy.USER_PRESENT, blocker == StartBlocker.NONE, false);
            }
        }

        private void tryDeferredUnlock() {
            if (!unlock.armed()) return;
            if (unlock.expired(SystemClock.uptimeMillis())) {
                log("unlock-expired");
                cancel(false);
                return;
            }
            StartBlocker blocker = startBlocker();
            if (BuildConfig.DEBUG) log("unlock-recheck=" + blocker + " flags=" + wallpaperFlags());
            if (blocker == StartBlocker.NONE) {
                dispatch(NativePolicy.TRY_DEFERRED_UNLOCK, true, false);
            } else if (!blocker.mayResolveDuringUnlock()) {
                cancel(false);
            } else if (visible) {
                // Some devices report keyguard gone after USER_PRESENT and the first recheck.
                main.removeCallbacks(unlockRecheck);
                main.postDelayed(unlockRecheck, UNLOCK_RECHECK_MILLIS);
            }
        }

        private void dispatch(int event, boolean allowed, boolean restorePoster) {
            if (!policyEnabled) return;
            int result = NativePolicy.reduceForPlayback(policyState, event, allowed);
            if (result < 0) {
                policyEnabled = false;
                clearDeferredUnlock();
                log("policy-error");
                stopPlayback(restorePoster);
                return;
            }
            policyState = result & 255;
            switch (result >> 8) {
                case NativePolicy.START:
                    clearDeferredUnlock();
                    playedSinceScreenOff = true;
                    startPlayback();
                    break;
                case NativePolicy.STOP:
                    stopPlayback(restorePoster);
                    break;
                case NativePolicy.WAIT:
                    unlock.arm(SystemClock.uptimeMillis());
                    if (visible) main.postDelayed(unlockRecheck, UNLOCK_RECHECK_MILLIS);
                    log("unlock-waiting");
                    break;
                default:
                    if ((allowed && event == NativePolicy.USER_PRESENT)
                            || event == NativePolicy.DEFER_USER_PRESENT) {
                        log("unlock-without-new-screen-off");
                    }
                    break;
            }
        }

        private void startPlayback() {
            if (isVideo) {
                needsPoster = true;
                if (orbitaTheme != null) {
                    log("orbita-unlock-start");
                    if (orbitaFailed) {
                        orbitaMotion.startZoom(SystemClock.uptimeMillis());
                        presentPoster(false);
                    } else {
                        drawOrbita(false, true);
                    }
                    return;
                }
                video = new VideoClip(main, this::onVideoEnded);
                communityAfterPending = community != null && community.after;
                video.start(community != null
                        ? CommunityPack.clip(StillMotionService.this, theme.id)
                        : FlymePack.file(StillMotionService.this, theme.id, ".mp4"), holder.getSurface());
                log("video-start");
                return;
            }
            long now = SystemClock.uptimeMillis();
            int motion = theme.readMotion(getResources());
            if (motion == 0) {
                log("motion-asset-invalid");
                cancel(true);
                return;
            }
            try {
                if (!backend.prepareAnimation(theme)) {
                    log("animation-bitmap-unavailable");
                    cancel(true);
                    return;
                }
            } catch (RuntimeException invalidImage) {
                log("animation-bitmap-failed");
                cancel(true);
                return;
            }
            active = new Playback(now, motion);
            if (BuildConfig.DEBUG) log("play-start policy=" + NativePolicy.status());
            active.run();
        }

        private void cancel(boolean restorePoster) {
            clearDeferredUnlock();
            dispatch(NativePolicy.CANCEL, false, restorePoster);
            stopPlayback(restorePoster);
        }

        private void clearDeferredUnlock() {
            if (unlock.armed()) {
                unlock.clear();
                main.removeCallbacks(unlockRecheck);
            }
        }

        private void stopPlayback(boolean restorePoster) {
            communityAfterPending = false;
            if (video != null) video.cancel();
            if (orbita != null) needsPoster = true;
            if (orbitaDrawingZoom) orbitaMotion.pauseAfter(SystemClock.uptimeMillis());
            closeOrbita();
            Playback stopped = active;
            active = null;
            if (stopped != null) {
                main.removeCallbacks(stopped);
                if (BuildConfig.DEBUG) {
                    log("play-stop submits=" + stopped.submits + " elapsed_ms="
                            + (SystemClock.uptimeMillis() - stopped.transition.start));
                }
            }
            backend.releaseAnimation();
            if (restorePoster && visible && power.isInteractive()) presentPoster(false);
        }

        private void onVideoEnded(VideoClip clip, String reason, int submitted) {
            if (video != clip) return;
            video = null;
            if (BuildConfig.DEBUG) log(reason + " submits=" + submitted);
            if (destroyed) return;
            boolean playAfter = communityAfterPending;
            communityAfterPending = false;
            if (communityLoopPlaying) {
                communityLoopPlaying = false;
                if (!"video-cancelled".equals(reason)) communityLoopId = null;
            }
            if (playAfter && "video-finished".equals(reason) && submitted > 0 && community != null && community.after) {
                communityLoopId = theme.id;
            }
            if (communityWakeVideo) {
                communityWakeVideo = false;
                communityWakeFinished = "video-finished".equals(reason) && submitted > 0;
            } else {
                dispatch(NativePolicy.FINISH, false, false);
            }
            if (visible && power.isInteractive()) presentPoster(false);
        }

        private void presentPoster(boolean forced) {
            if (destroyed || holder == null || video != null || (!forced && (!visible || !needsPoster))) return;
            if (community != null) {
                if (community.after && theme.id.equals(communityLoopId) && orbitaCanAnimate()
                        && !keyguard.isKeyguardLocked() && holder.getSurface().isValid()
                        && (wallpaperFlags() & WallpaperManager.FLAG_SYSTEM) != 0) {
                    needsPoster = true;
                    communityLoopPlaying = true;
                    video = new VideoClip(main, this::onVideoEnded);
                    video.start(CommunityPack.afterClip(StillMotionService.this, theme.id), holder.getSurface(), true);
                    log("community-after-loop");
                    return;
                }
                boolean first = !isPreview() && keyguard.isKeyguardLocked()
                        && (!community.wake || !communityWakeFinished);
                needsPoster = !VideoPoster.present(holder.getSurface(),
                        CommunityPack.poster(StillMotionService.this, theme.id, first));
                return;
            }
            if (CommunityPack.isCustom(theme.id) || Orbita.isOrbita(theme.id) && orbitaTheme == null) return;
            if (orbitaTheme != null) {
                if (!orbitaFailed) {
                    drawOrbita(forced);
                    return;
                }
                boolean initial = !isPreview()
                        && (keyguard.isKeyguardLocked() || orbitaMotion.waitingForUnlock());
                needsPoster = !VideoPoster.present(holder.getSurface(), orbitaTheme.poster(initial ? "wake" : "home"));
                return;
            }
            if (isVideo && FlymePack.contains(StillMotionService.this, theme.id)) {
                needsPoster = !VideoPoster.present(holder.getSurface(),
                        FlymePack.file(StillMotionService.this, theme.id, ".webp"));
                log(needsPoster ? "video-poster-failed" : "poster video");
                return;
            }
            if (isVideo) return;
            needsPoster = !backend.presentPoster(holder, theme);
            if (BuildConfig.DEBUG) {
                log(needsPoster ? "poster-failed" : "poster hardware=" + backend.hasHardwareProducer());
            }
        }

        private void log(String event) {
            if (BuildConfig.DEBUG) {
                String line = "t=" + SystemClock.uptimeMillis() + " engine=" + id
                        + " surface=" + generation + " " + event;
                Log.d(TAG, line);
                DebugEvents.add(line);
            }
        }

        private final class Playback implements Runnable {
            private final int surfaceGeneration = generation;
            private final Transition transition;
            private int submits;

            Playback(long start, int motion) {
                transition = new Transition(start, motion);
            }

            @Override
            public void run() {
                if (active != this || generation != surfaceGeneration) return;
                if (!visible || holder == null || !holder.getSurface().isValid()) {
                    cancel(false);
                    return;
                }
                long now = SystemClock.uptimeMillis();
                if (transition.finished(now)) {
                    if (submits > 0 && backend.presentAnimation(holder, 1f)) {
                        submits++;
                        needsPoster = false;
                        log("poster-final");
                    }
                    dispatch(NativePolicy.FINISH, false, true);
                    return;
                }
                needsPoster = true;
                if (!backend.presentAnimation(holder, transition.zoom(now))) {
                    log("animation-submit-failed");
                    cancel(true);
                    return;
                }
                submits++;
                if (!main.postAtTime(this, transition.nextFrame(SystemClock.uptimeMillis()))) {
                    cancel(true);
                }
            }
        }
    }
}
