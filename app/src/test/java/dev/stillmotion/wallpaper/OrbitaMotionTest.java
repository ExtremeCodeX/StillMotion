package dev.stillmotion.wallpaper;

public final class OrbitaMotionTest {
    public static void main(String[] args) {
        OrbitaMotion motion = new OrbitaMotion();
        motion.screenOff();
        require(motion.waitingForUnlock() && motion.wakeProgress(100) == 0, "screen-off frame");
        motion.startWake(100);
        require(motion.wakeProgress(100) == 0, "wake starts at top");
        require(motion.wakeProgress(1300) == 1, "wake travel settles");
        require(motion.phase(60_001) > motion.phase(60_000), "waves continue while locked");
        float before = motion.phase(60_000);
        motion.startZoom(60_000);
        require(motion.phase(60_000) == before && motion.zoomProgress(60_000) == 0, "continuous unlock");
        require(motion.zooming(61_049) && !motion.zooming(61_050), "1050 ms zoom");
        require(motion.zoomProgress(61_050) == 1 && motion.phase(65_000) == motion.phase(61_050),
                "home holds a single frame");
        motion.screenOff();
        motion.startWake(70_000);
        float partial = motion.wakeProgress(70_300);
        motion.startZoom(70_300);
        require(motion.wakeProgress(70_300) == partial, "early unlock retains position");
        require(motion.wakeProgress(71_350) == 1, "early unlock still reaches final position");
        motion.screenOff();
        motion.startWake(80_000);
        motion.startZoom(82_000);
        motion.finishZoom();
        require(!motion.zooming(82_020) && motion.zoomProgress(82_020) == 1,
                "cancelled zoom does not resume when the home becomes visible");
        require(motion.phase(82_020) == motion.phase(83_050), "cancelled zoom holds final phase");
        motion.screenOff();
        motion.startWake(90_000);
        motion.startZoom(92_000, true);
        require(!motion.playingAfter(93_049) && motion.playingAfter(93_050), "post starts at the zoom boundary");
        require(motion.phase(93_051) > motion.phase(93_050), "post phase is continuous");
        require(motion.afterStrength(93_050) > .99f, "waves are visible after zoom");
        require(motion.playingAfter(9_000_000), "post stays active without a deadline");
        float stopped = motion.phase(94_000);
        motion.pauseAfter(94_000);
        require(motion.phase(98_000) == stopped, "hidden loop pauses");
        motion.resumeAfter(98_000);
        require(motion.phase(98_000) == stopped && motion.phase(98_001) > stopped, "visible loop resumes");
        motion.screenOff();
        require(!motion.playingAfter(100_000), "screen-off clears the session");
        motion.startZoom(100_000, true);
        motion.pauseAfter(100_300);
        motion.resumeAfter(100_500);
        require(!motion.zooming(100_500) && motion.playingAfter(100_500), "early interruption resumes the loop without replaying zoom");
        motion.disableAfter(101_000);
        require(!motion.playingAfter(101_001) && motion.phase(102_000) == motion.phase(101_000),
                "turning the option off freezes the loop");
        System.out.println("Órbita: loop de bloqueio, transição contínua e repouso verificados");
    }

    private static void require(boolean matches, String name) {
        if (!matches) throw new AssertionError(name);
    }
}
