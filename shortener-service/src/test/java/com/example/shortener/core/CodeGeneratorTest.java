package com.example.shortener.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CodeGeneratorTest {

    @Test
    void generatesBase62CodesOfConfiguredLength() {
        CodeGenerator gen = new CodeGenerator(7);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String code = gen.next();
            assertEquals(7, code.length());
            assertTrue(code.chars().allMatch(c -> CodeGenerator.ALPHABET.indexOf(c) >= 0));
            seen.add(code);
        }
        assertTrue(seen.size() >= 9_999, "codes should practically never collide at this volume");
    }

    @Test
    void rejectsTooShortLength() {
        assertThrows(IllegalArgumentException.class, () -> new CodeGenerator(3));
    }

    @Test
    void aliasRules() {
        assertNull(CodeGenerator.aliasProblem("launch-2026"));
        assertNull(CodeGenerator.aliasProblem("A_b"));
        assertNotNull(CodeGenerator.aliasProblem("ab"));
        assertNotNull(CodeGenerator.aliasProblem("-leading"));
        assertNotNull(CodeGenerator.aliasProblem("has space"));
        assertNotNull(CodeGenerator.aliasProblem("a".repeat(33)));
        assertTrue(CodeGenerator.aliasProblem("Actuator").contains("reserved"));
        assertTrue(CodeGenerator.aliasProblem("api").contains("reserved"));
    }
}
