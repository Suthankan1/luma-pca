package com.physicscube.luma.identity.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {
    private static final String EMAIL = "kavindi@example.invalid";

    @Test
    void acceptsLongPassphraseWithoutCharacterClassRules() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("quiet river under bridge", EMAIL));
    }

    @Test
    void acceptsBoundaryLengths() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("ab".repeat(PasswordPolicy.MIN_LENGTH / 2), EMAIL));
        assertDoesNotThrow(() -> PasswordPolicy.validate("ab".repeat(PasswordPolicy.MAX_LENGTH / 2), EMAIL));
    }

    @Test
    void rejectsMissingShortAndOverlongPasswords() {
        assertThrows(PasswordPolicyException.class, () -> PasswordPolicy.validate(null, EMAIL));
        assertThrows(PasswordPolicyException.class, () -> PasswordPolicy.validate(" ".repeat(20), EMAIL));
        assertThrows(PasswordPolicyException.class, () -> PasswordPolicy.validate("only11chars", EMAIL));
        assertThrows(PasswordPolicyException.class,
                () -> PasswordPolicy.validate("ab".repeat(PasswordPolicy.MAX_LENGTH / 2) + "c", EMAIL));
    }

    @Test
    void rejectsSingleRepeatedCharacter() {
        assertThrows(PasswordPolicyException.class, () -> PasswordPolicy.validate("a".repeat(20), EMAIL));
    }

    @Test
    void rejectsPasswordContainingEmailNameInAnyCase() {
        assertThrows(PasswordPolicyException.class, () -> PasswordPolicy.validate("walks with KAVINDI daily", EMAIL));
    }

    @Test
    void shortEmailNamesAreNotCompared() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("abstract-table-lamp", "ab@example.invalid"));
    }

    @Test
    void rejectionMessageNeverEchoesThePassword() {
        String candidate = "kavindi walks to class";
        PasswordPolicyException e = assertThrows(PasswordPolicyException.class,
                () -> PasswordPolicy.validate(candidate, EMAIL));
        assertFalse(e.getMessage().contains(candidate));
    }
}
