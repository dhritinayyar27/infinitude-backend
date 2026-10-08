package com.infinitude.service;

import com.infinitude.model.Section;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TocStructureTests {
    private Section topic(int level) {
        Section section = new Section();
        section.setTitle("Topic");
        section.setLevel(level);
        return section;
    }

    @Test
    void numberingPreservesPreorderHierarchy() {
        assertEquals(List.of("1", "1.1", "1.2", "2", "2.1", "2.1.1"),
                TocStructure.numbering(List.of(topic(1), topic(2), topic(2), topic(1), topic(2), topic(3))));
    }

    @Test
    void rejectsMissingParentsEmptyTocAndExcessiveDepth() {
        assertThrows(IllegalArgumentException.class, () -> TocStructure.numbering(List.of()));
        assertThrows(IllegalArgumentException.class, () -> TocStructure.numbering(List.of(topic(2))));
        assertThrows(IllegalArgumentException.class, () -> TocStructure.numbering(List.of(topic(1), topic(3))));
        assertThrows(IllegalArgumentException.class, () -> TocStructure.numbering(List.of(topic(6))));
    }
}
