#![cfg_attr(all(target_os = "android", not(test)), no_std)]

#[cfg(all(target_os = "android", not(test)))]
#[panic_handler]
fn panic(_info: &core::panic::PanicInfo<'_>) -> ! {
    unsafe extern "C" {
        fn abort() -> !;
    }
    // SAFETY: Android's libc supplies abort(), which never returns. Our JNI entry
    // points validate input and use no panicking operations for ordinary failures.
    unsafe { abort() }
}

const SURFACE: u8 = 1 << 0;
const VISIBLE: u8 = 1 << 1;
const SCREEN_WAS_OFF: u8 = 1 << 2;
const WAITING_FOR_VISIBILITY: u8 = 1 << 3;
const PLAYING: u8 = 1 << 4;
const DESTROYED: u8 = 1 << 5;
const KNOWN_FLAGS: u8 = (DESTROYED << 1) - 1;

#[derive(Clone, Copy)]
#[repr(i32)]
enum Event {
    Visible = 1,
    Hidden = 2,
    SurfaceReady = 3,
    SurfaceLost = 4,
    ScreenOff = 5,
    ScreenOn = 6,
    UserPresent = 7,
    Finish = 8,
    Cancel = 9,
    Destroy = 10,
    DeferUserPresent = 11,
    TryDeferredUnlock = 12,
}

impl Event {
    fn parse(value: i32) -> Option<Self> {
        Some(match value {
            1 => Self::Visible,
            2 => Self::Hidden,
            3 => Self::SurfaceReady,
            4 => Self::SurfaceLost,
            5 => Self::ScreenOff,
            6 => Self::ScreenOn,
            7 => Self::UserPresent,
            8 => Self::Finish,
            9 => Self::Cancel,
            10 => Self::Destroy,
            11 => Self::DeferUserPresent,
            12 => Self::TryDeferredUnlock,
            _ => return None,
        })
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
enum Effect {
    None = 0,
    Start = 1,
    Stop = 2,
    Wait = 3,
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
struct Policy(u8);

impl Policy {
    fn ready(self) -> bool {
        self.0 & (SURFACE | VISIBLE) == SURFACE | VISIBLE
    }

    fn reduce(self, event: Event, allowed: bool) -> (Self, Effect) {
        let mut next = self.0;
        if next & DESTROYED != 0 {
            return (self, Effect::None);
        }
        match event {
            Event::Visible => next |= VISIBLE,
            Event::Hidden => next &= !(VISIBLE | PLAYING | WAITING_FOR_VISIBILITY),
            Event::SurfaceReady => next = (next | SURFACE) & !PLAYING,
            Event::SurfaceLost => next &= !(SURFACE | PLAYING | WAITING_FOR_VISIBILITY),
            Event::ScreenOff => {
                next = (next | SCREEN_WAS_OFF) & !(WAITING_FOR_VISIBILITY | PLAYING);
            }
            Event::ScreenOn => {}
            Event::UserPresent => {
                if next & SCREEN_WAS_OFF != 0 && self.ready() && allowed {
                    next |= PLAYING;
                }
                next &= !SCREEN_WAS_OFF;
            }
            Event::DeferUserPresent if next & SCREEN_WAS_OFF != 0 => {
                next = (next & !SCREEN_WAS_OFF) | WAITING_FOR_VISIBILITY;
            }
            Event::TryDeferredUnlock if next & WAITING_FOR_VISIBILITY != 0 => {
                if self.ready() && allowed {
                    next = (next & !WAITING_FOR_VISIBILITY) | PLAYING;
                }
            }
            Event::DeferUserPresent | Event::TryDeferredUnlock => {}
            Event::Finish | Event::Cancel => next &= !(PLAYING | WAITING_FOR_VISIBILITY),
            Event::Destroy => next = DESTROYED,
        }
        let effect = match (self.0 & PLAYING != 0, next & PLAYING != 0) {
            (false, true) => Effect::Start,
            (true, false) => Effect::Stop,
            _ if self.0 & WAITING_FOR_VISIBILITY == 0 && next & WAITING_FOR_VISIBILITY != 0 => {
                Effect::Wait
            }
            _ => Effect::None,
        };
        (Self(next), effect)
    }

    fn from_wire(bits: i32) -> Option<Self> {
        let bits = u8::try_from(bits).ok()?;
        let state = Self(bits);
        let pending_consistent =
            bits & WAITING_FOR_VISIBILITY == 0 || bits & (SCREEN_WAS_OFF | PLAYING) == 0;
        let playback_consistent = bits & PLAYING == 0
            || (state.ready() && bits & (SCREEN_WAS_OFF | WAITING_FOR_VISIBILITY) == 0);
        let destroy_consistent = bits & DESTROYED == 0 || bits == DESTROYED;
        if bits & !KNOWN_FLAGS != 0
            || !pending_consistent
            || !playback_consistent
            || !destroy_consistent
        {
            return None;
        }
        Some(state)
    }
}

// v1 descriptor: "S", version, backend, fps, duration(ms, big-endian),
// initial zoom over 100%, reserved. Zero is the static-only fallback.
fn parse_theme(encoded: i64) -> i32 {
    let [magic, version, backend, fps, high, low, zoom, reserved] = encoded.to_be_bytes();
    let duration = u16::from_be_bytes([high, low]);
    if magic != b'S'
        || version != 1
        || backend != 1
        || reserved != 0
        || !(12..=30).contains(&fps)
        || !(250..=2000).contains(&duration)
        || !(1..=25).contains(&zoom)
    {
        return 0;
    }
    (i32::from(duration) << 16) | (i32::from(fps) << 8) | i32::from(zoom)
}

// JNI uses only primitive values. The two opaque JNI arguments are never accessed.
// SAFETY: the exported names are unique to our class and match its static declarations.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_stillmotion_wallpaper_NativePolicy_reduce(
    _env: *mut core::ffi::c_void,
    _class: *mut core::ffi::c_void,
    state: i32,
    event: i32,
    allowed: u8,
) -> i32 {
    let (Some(state), Some(event)) = (Policy::from_wire(state), Event::parse(event)) else {
        return -1;
    };
    let (next, effect) = state.reduce(event, allowed != 0);
    i32::from(next.0) | ((effect as i32) << 8)
}

// SAFETY: the exported name is unique to our class and matches its static declaration.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_stillmotion_wallpaper_NativePolicy_parseTheme(
    _env: *mut core::ffi::c_void,
    _class: *mut core::ffi::c_void,
    descriptor: i64,
) -> i32 {
    parse_theme(descriptor)
}

#[cfg(test)]
mod tests {
    use super::*;
    use Event::*;

    fn feed(events: &[Event]) -> (Policy, Effect) {
        events
            .iter()
            .fold((Policy::default(), Effect::None), |(state, _), event| {
                state.reduce(*event, true)
            })
    }

    #[test]
    fn starts_only_after_screen_off_and_user_present() {
        for events in [
            &[SurfaceReady, Visible, UserPresent][..],
            &[SurfaceReady, Visible, ScreenOn, UserPresent],
            &[SurfaceReady, ScreenOff, ScreenOn, UserPresent, Visible],
        ] {
            let (state, effect) = feed(events);
            assert_eq!(effect, Effect::None);
            assert_eq!(state.0 & PLAYING, 0);
        }
        assert_eq!(
            feed(&[SurfaceReady, Visible, ScreenOff, ScreenOn, UserPresent]).1,
            Effect::Start
        );
        assert_eq!(
            feed(&[SurfaceReady, Visible, ScreenOff, UserPresent]).1,
            Effect::Start
        );
        let (playing, _) = feed(&[SurfaceReady, Visible, ScreenOff, UserPresent]);
        let (new_session, stopped) = playing.reduce(ScreenOff, false);
        assert_eq!(stopped, Effect::Stop);
        assert_eq!(new_session.reduce(UserPresent, false).1, Effect::None);
    }

    #[test]
    fn deferred_unlock_can_only_start_once_when_visibility_arrives() {
        let (pending, effect) = feed(&[SurfaceReady, ScreenOff, DeferUserPresent]);
        assert_eq!(effect, Effect::Wait);
        assert_eq!(
            pending.reduce(DeferUserPresent, false),
            (pending, Effect::None)
        );
        assert_eq!(
            pending.reduce(TryDeferredUnlock, true),
            (pending, Effect::None)
        );
        let (visible, _) = pending.reduce(Visible, false);
        assert_eq!(
            visible.reduce(TryDeferredUnlock, false),
            (visible, Effect::None)
        );
        let (playing, effect) = visible.reduce(TryDeferredUnlock, true);
        assert_eq!(effect, Effect::Start);
        let (stopped, _) = playing.reduce(Hidden, false);
        let (visible_again, _) = stopped.reduce(Visible, false);
        assert_eq!(
            visible_again.reduce(TryDeferredUnlock, true).1,
            Effect::None
        );
        assert_eq!(visible_again.reduce(UserPresent, true).1, Effect::None);
    }

    #[test]
    fn deferred_unlock_is_discarded_on_cancel_surface_loss_or_hide() {
        let (pending, _) = feed(&[SurfaceReady, ScreenOff, DeferUserPresent]);
        for interruption in [Cancel, SurfaceLost, Hidden, Destroy] {
            let (state, _) = pending.reduce(interruption, false);
            let (state, _) = state.reduce(SurfaceReady, false);
            let (state, _) = state.reduce(Visible, false);
            assert_eq!(state.reduce(TryDeferredUnlock, true).1, Effect::None);
            assert_eq!(state.reduce(UserPresent, true).1, Effect::None);
        }
    }

    #[test]
    fn cancellation_and_completion_consume_the_unlock() {
        for event in [Hidden, SurfaceLost, SurfaceReady, Finish, Cancel, Destroy] {
            let (playing, _) = feed(&[SurfaceReady, Visible, ScreenOff, ScreenOn, UserPresent]);
            let (state, effect) = playing.reduce(event, false);
            assert_eq!(effect, Effect::Stop);
            let (state, _) = state.reduce(Visible, true);
            let (state, _) = state.reduce(SurfaceReady, true);
            assert_eq!(state.reduce(UserPresent, true).1, Effect::None);
        }
    }

    #[test]
    fn ineligible_unlock_is_spent_and_next_wake_can_play() {
        let (state, _) = feed(&[SurfaceReady, Visible, ScreenOff, ScreenOn, ScreenOn]);
        let (state, effect) = state.reduce(UserPresent, false);
        assert_eq!(effect, Effect::None);
        let (state, effect) = state.reduce(UserPresent, true);
        assert_eq!(effect, Effect::None);
        let (state, _) = state.reduce(ScreenOff, true);
        let (state, _) = state.reduce(ScreenOn, true);
        assert_eq!(state.reduce(UserPresent, true).1, Effect::Start);
    }

    #[test]
    fn instances_keep_preview_and_home_state_separate() {
        let (home, _) = feed(&[SurfaceReady, Visible, ScreenOff, ScreenOn]);
        let (preview, _) = feed(&[SurfaceReady, Visible, ScreenOff, ScreenOn]);
        assert_eq!(preview.reduce(UserPresent, false).1, Effect::None);
        assert_eq!(home.reduce(UserPresent, true).1, Effect::Start);
    }

    #[test]
    fn checks_reachable_transitions_for_valid_jni_values() {
        let mut seen = [false; 64];
        seen[0] = true;
        for _ in 0..seen.len() {
            for state in 0..seen.len() {
                if !seen[state] {
                    continue;
                }
                let policy = Policy::from_wire(state as i32).unwrap();
                for event in 1..=12 {
                    for allowed in [false, true] {
                        let (next, effect) = policy.reduce(Event::parse(event).unwrap(), allowed);
                        assert_eq!(Policy::from_wire(i32::from(next.0)), Some(next));
                        if !next.ready() || next.0 == DESTROYED {
                            assert_eq!(next.0 & PLAYING, 0);
                            assert_ne!(effect, Effect::Start);
                        }
                        if policy.0 == DESTROYED {
                            assert_eq!(policy, next);
                        }
                        seen[usize::from(next.0)] = true;
                    }
                }
            }
        }
    }

    fn jni_reduce(state: i32, event: i32, allowed: bool) -> i32 {
        Java_dev_stillmotion_wallpaper_NativePolicy_reduce(
            core::ptr::null_mut(),
            core::ptr::null_mut(),
            state,
            event,
            u8::from(allowed),
        )
    }

    #[test]
    fn jni_rejects_invalid_values_and_destroy_is_idempotent() {
        for invalid in [-1, 64, 256, i32::MAX, 12, 16, 24, 33] {
            assert_eq!(jni_reduce(invalid, 1, true), -1);
        }
        assert_eq!(jni_reduce(0, i32::MAX, false), -1);
        let destroyed = jni_reduce(0, 10, true);
        assert_eq!(jni_reduce(destroyed, 10, true), destroyed);
        assert_eq!(jni_reduce(destroyed, 7, true), destroyed);
    }

    #[test]
    fn rapid_sessions_are_finite_and_concurrent_instances_independent() {
        std::thread::scope(|group| {
            for cancel in [Hidden, SurfaceLost, Cancel, Finish] {
                group.spawn(move || {
                    let mut state = 0;
                    for _ in 0..150 {
                        for event in [SurfaceReady, Visible, ScreenOff, ScreenOn] {
                            state = jni_reduce(state, event as i32, true) & 255;
                        }
                        let playing = jni_reduce(state, UserPresent as i32, true);
                        assert_eq!(playing >> 8, Effect::Start as i32);
                        let stopped = jni_reduce(playing & 255, cancel as i32, true);
                        assert_eq!(stopped >> 8, Effect::Stop as i32);
                        state = stopped & 255;
                        assert_eq!(jni_reduce(state, UserPresent as i32, true), state);
                    }
                });
            }
        });
    }

    #[test]
    fn rejects_truncated_corrupt_and_unsupported_theme_descriptors() {
        let good = [b'S', 1, 1, 24, 4, 176, 15, 0];
        assert_eq!(
            parse_theme(i64::from_be_bytes(good)),
            (1200 << 16) | (24 << 8) | 15
        );
        for (index, byte) in [(0, 0), (1, 2), (2, 2), (3, 31), (4, 8), (6, 26), (7, 1)] {
            let mut corrupt = good;
            corrupt[index] = byte;
            assert_eq!(parse_theme(i64::from_be_bytes(corrupt)), 0);
        }
        assert_eq!(parse_theme(0), 0);
    }
}
