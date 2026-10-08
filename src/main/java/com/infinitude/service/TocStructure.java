package com.infinitude.service;

import com.infinitude.model.Section;
import java.util.ArrayList;
import java.util.List;

public final class TocStructure {
    private TocStructure() {}

    public static List<String> numbering(List<Section> sections) {
        if (sections == null || sections.isEmpty() || sections.size() > 100) {
            throw new IllegalArgumentException("TOC must contain between 1 and 100 topics.");
        }
        int[] counters = new int[5];
        int previous = 0;
        List<String> numbers = new ArrayList<>();
        for (Section section : sections) {
            int level = section.getLevel();
            if (section.getTitle() == null || section.getTitle().isBlank()
                    || level < 1 || level > 5 || level > previous + 1) {
                throw new IllegalArgumentException("TOC titles must be non-blank and hierarchy cannot skip a level.");
            }
            counters[level - 1]++;
            for (int i = level; i < counters.length; i++) counters[i] = 0;
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < level; i++) parts.add(Integer.toString(counters[i]));
            numbers.add(String.join(".", parts));
            previous = level;
        }
        return numbers;
    }
}
