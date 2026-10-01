package dev.sculptory.fabric.client.editor.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.Translator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The grouping, naming and filtering the key sheet and the Keys window share. */
class KeyListingTest {
    @Test
    void everyGroupOnceInGroupOrderWithAllItsActions() {
        List<KeyListing.Section> sections = KeyListing.sections();
        assertEquals(List.of(KeyAction.Group.values()), sections.stream().map(KeyListing.Section::group).toList());
        EnumSet<KeyAction> seen = EnumSet.noneOf(KeyAction.class);
        for (KeyListing.Section section : sections) {
            for (KeyAction action : section.actions()) {
                assertEquals(section.group(), action.group());
                assertTrue(seen.add(action), action.name());
            }
        }
        assertEquals(EnumSet.allOf(KeyAction.class), seen);
        KeyListing.Section windows = sections.get(sections.size() - 1);
        assertEquals(KeyAction.Group.WINDOWS, windows.group());
        assertTrue(windows.actions().containsAll(List.of(KeyAction.COMMAND_SEARCH, KeyAction.FOCUS_NEXT_WINDOW)),
                "the actions appended after Remove node are under Windows, not a second Windows heading");
        assertEquals(sections.stream().flatMap(section -> section.actions().stream()).toList(),
                KeyListing.inGroupOrder());
        assertEquals("sculptory.keys.group.clipboard", KeyListing.groupKey(KeyAction.Group.CLIPBOARD));
    }

    @Test
    void paletteSlotsAreNamedAfterTheirToolsOtherActionsByTheirLabels() {
        assertEquals("sculptory.keys.slot[2,Raise]",
                KeyListing.name(KeyAction.toolSlot(2), Translator.KEYS, slot -> Optional.of("Raise")));
        assertEquals(KeyAction.toolSlot(2).labelKey(),
                KeyListing.name(KeyAction.toolSlot(2), Translator.KEYS, slot -> Optional.empty()), "no tool known");
        assertEquals(KeyAction.UNDO.labelKey(),
                KeyListing.name(KeyAction.UNDO, Translator.KEYS, slot -> Optional.of("Raise")));
    }

    @Test
    void typedWordsStartWordsOfTheNameOrTheTextStartsAKey() {
        assertTrue(KeyListing.matches("", "Undo", List.of("Ctrl+Z")), "nothing typed: everything");
        assertTrue(KeyListing.matches("und", "Undo", List.of("Ctrl+Z")));
        assertTrue(KeyListing.matches("UNDO", "Undo", List.of()), "case is ignored");
        assertTrue(KeyListing.matches("undo a", "Undo anyway", List.of()), "every word starts a word");
        assertTrue(KeyListing.matches("sym cen", "Set symmetry centre", List.of()));
        assertFalse(KeyListing.matches("ndo", "Undo", List.of("Ctrl+Z")), "only word starts");
        assertFalse(KeyListing.matches("undo z", "Undo anyway", List.of()));
        assertTrue(KeyListing.matches("ctrl+d", "Deselect", List.of("Ctrl+D")));
        assertTrue(KeyListing.matches("CTRL + D", "Deselect", List.of("Ctrl+D")), "spaces and case ignored in keys");
        assertTrue(KeyListing.matches("ctrl+", "Deselect", List.of("Ctrl+D")), "a key typed so far");
        assertTrue(KeyListing.matches("f1", "Key sheet", List.of("F1")));
        assertFalse(KeyListing.matches("f2", "Key sheet", List.of("F1")));
        assertFalse(KeyListing.matches("ctrl+d", "Undo", List.of("Ctrl+Z")));
        assertTrue(KeyListing.matches("shift", "Flip", List.of("F", "Shift+F")), "any of the keys");
    }
}
