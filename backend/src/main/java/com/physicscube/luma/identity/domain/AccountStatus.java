package com.physicscube.luma.identity.domain;

public enum AccountStatus {
    /** Created by staff or import; no password yet, cannot sign in. */
    PENDING_ACTIVATION,
    ACTIVE,
    /** Sign-in blocked; academic evidence linked to the account is retained. */
    DISABLED
}
