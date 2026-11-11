package com.physicscube.luma.students;

import java.util.List;

/** @param nextAfter pass as {@code after} to get the next page; {@code null} on the last page */
public record StudentPage(List<StudentView> items, String nextAfter) {
}
