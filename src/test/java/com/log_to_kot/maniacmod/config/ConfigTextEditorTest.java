package com.log_to_kot.maniacmod.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Правка одного значення в yml не має чіпати коментарі, порядок і сусідні блоки. */
class ConfigTextEditorTest {

    private static final String NESTED = String.join("\n",
        "# шапка",
        "maniac_selection:",
        "  # хто",
        "  playerMode: RANDOM",
        "  fixedManiacId: ''",
        "",
        "maniac:",
        "  # перезарядка",
        "  trapPlaceCooldownTicks: 500  # 25 с",
        "  playerMode: 7",
        "");

    @Test
    void replacesValueInsideOwnBlockOnly() {
        String out = ConfigTextEditor.replaceValue(NESTED, "maniac", "playerMode", "9");
        assertEquals(NESTED.replace("  playerMode: 7", "  playerMode: 9"), out);
    }

    @Test
    void keepsTrailingCommentAndPrecedingComments() {
        String out = ConfigTextEditor.replaceValue(NESTED, "maniac", "trapPlaceCooldownTicks", "600");
        assertEquals(NESTED.replace("500  # 25 с", "600  # 25 с"), out);
    }

    @Test
    void replacesQuotedStringAndKeepsItsComment() {
        String text = "enabled: true\nprofile: 'DEMON'  # пресет\n";
        assertEquals("enabled: true\nprofile: 'TITAN'  # пресет\n",
            ConfigTextEditor.replaceValue(text, null, "profile", "'TITAN'"));
    }

    @Test
    void hashInsideQuotesIsNotAComment() {
        String text = "id: 'a # b'\n";
        assertEquals("id: 'x'\n", ConfigTextEditor.replaceValue(text, null, "id", "'x'"));
    }

    @Test
    void flatFileAndCrlfArePreserved() {
        String text = "# c\r\nmaxHp: 100\r\nslots: 4\r\n";
        assertEquals("# c\r\nmaxHp: 150\r\nslots: 4\r\n",
            ConfigTextEditor.replaceValue(text, null, "maxHp", "150"));
    }

    @Test
    void unknownKeyOrBlockGivesNull() {
        assertNull(ConfigTextEditor.replaceValue(NESTED, "maniac", "nope", "1"));
        assertNull(ConfigTextEditor.replaceValue(NESTED, "missing", "playerMode", "1"));
        assertNull(ConfigTextEditor.replaceValue("list:\n  - a\n", null, "list", "1"));
    }

    @Test
    void commentedOutKeyIsNotTouched() {
        String text = "# maxHp: 1\nmaxHp: 2\n";
        assertEquals("# maxHp: 1\nmaxHp: 3\n", ConfigTextEditor.replaceValue(text, null, "maxHp", "3"));
    }
}
