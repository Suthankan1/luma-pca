/**
 * Identity: persistent accounts, academy-level role grants, password hashing and
 * activation invitations. Other modules use
 * {@link com.physicscube.luma.identity.AccountService} and
 * {@link com.physicscube.luma.identity.InvitationService}, never the repositories
 * or entities directly. Password sign-in issues RS256 access tokens bound to a
 * rotating refresh session; there is no second factor. Passwords are reset by emailed
 * link or changed with the current one.
 */
package com.physicscube.luma.identity;
