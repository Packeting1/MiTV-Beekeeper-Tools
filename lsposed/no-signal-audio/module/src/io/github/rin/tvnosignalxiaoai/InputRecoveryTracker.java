package io.github.rin.tvnosignalxiaoai;

import java.util.HashMap;
import java.util.Map;

/** Detects disconnected/standby -> connected edges for every external input. */
final class InputRecoveryTracker {
    private final Map<String, Integer> states = new HashMap<String, Integer>();

    boolean update(String input, int state) {
        Integer previous = states.put(input, Integer.valueOf(state));
        return state == 0 && previous != null && previous.intValue() != 0;
    }

    void removed(String input) {
        if (states.containsKey(input)) states.put(input, Integer.valueOf(2));
    }
}
