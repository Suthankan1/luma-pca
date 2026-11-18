package com.physicscube.luma.academy;

/**
 * One student expected in an Academy class: the Academy's own ID (pcaid) and, when the student table is
 * authorised and has exactly one live row for that ID, the name and email. Nothing else is read.
 *
 * @param detailRows live student rows found for the ID: 0 (unknown), 1, or more (ambiguous: name and email withheld)
 */
public record AcademyStudent(String studentId, String name, String email, int detailRows) {}
