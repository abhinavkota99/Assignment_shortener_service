package com.example.shortener.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class CodeGeneratorTest {

    @Test
    public void generatesBase62CodesOfConfiguredLength() {
        CodeGenerator gen = new CodeGenerator(7);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String code = gen.next();
            assertEquals(7, code.length());
            assertTrue(code.chars().allMatch(c -> CodeGenerator.ALPHABET.indexOf(c) >= 0));
            seen.add(code);
        }
        assertTrue("codes should practically never collide at this volume", seen.size() >= 9_999);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTooShortLength() {
        new CodeGenerator(3);
    }

    @Test
    public void aliasRules() {
        assertNull(CodeGenerator.aliasProblem("launch-2026"));
        assertNull(CodeGenerator.aliasProblem("A_b"));
        assertNotNull(CodeGenerator.aliasProblem("ab"));
        assertNotNull(CodeGenerator.aliasProblem("-leading"));
        assertNotNull(CodeGenerator.aliasProblem("has space"));
        assertNotNull(CodeGenerator.aliasProblem("a".repeat(33)));
        assertTrue(CodeGenerator.aliasProblem("Metrics").contains("reserved"));
        assertTrue(CodeGenerator.aliasProblem("api").contains("reserved"));
    }
}
