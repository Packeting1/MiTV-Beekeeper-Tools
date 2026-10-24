package com.beekeeper.nosignalaudiomode;

import java.util.HashMap;
import java.util.Map;

/** 检测每个外部输入从断开/待机变为已连接的瞬间。 */
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
