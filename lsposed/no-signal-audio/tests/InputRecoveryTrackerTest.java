package io.github.rin.tvnosignalxiaoai;

public final class InputRecoveryTrackerTest {
    private static int checks;
    private static void expect(boolean expected, boolean actual, String scenario) {
        checks++;
        if (actual != expected) throw new AssertionError(scenario);
    }
    public static void main(String[] args) {
        InputRecoveryTracker tracker = new InputRecoveryTracker();
        expect(false, tracker.update("HDMI-1", 0), "Startup connected input must not wake");
        expect(false, tracker.update("HDMI-1", 0), "Duplicate connected notification must not wake");
        expect(false, tracker.update("HDMI-1", 1), "Loss alone must not wake");
        expect(true, tracker.update("HDMI-1", 0), "Standby input recovery must wake");
        expect(false, tracker.update("HDMI-2", 2), "Second port starts disconnected");
        expect(true, tracker.update("HDMI-2", 0), "Other port must wake without selecting or arming it");
        expect(false, tracker.update("DP", 1), "DP starts in standby");
        expect(true, tracker.update("DP", 0), "DP recovery must wake");
        tracker.removed("HDMI-2");
        expect(true, tracker.update("HDMI-2", 0), "Removed input reappearing must wake");
        expect(false, tracker.update("HDMI-2", 0), "Reappearance must not repeatedly wake");
        tracker.removed("unknown");
        expect(false, tracker.update("unknown", 0), "Unknown input must not invent a recovery edge");
        System.out.println("Input recovery checks passed: " + checks);
    }
}
