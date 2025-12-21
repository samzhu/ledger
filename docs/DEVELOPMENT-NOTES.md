# Development Notes

This document records important development notes, known issues, and best practices discovered during development.

---

## Java 25 + SpEL/Thymeleaf Compatibility Issues

### Problem Description

When running on **Java 25**, Spring Expression Language (SpEL) cannot correctly resolve methods on `java.util.ImmutableCollections$ListN` (the immutable list returned by Java 16+ `.toList()` method).

### Error Symptoms

```
SpelEvaluationException: EL1004E: Method call: Method isEmpty() cannot be found on type java.util.ImmutableCollections$ListN
SpelEvaluationException: EL1004E: Method call: Method size() cannot be found on type java.util.ImmutableCollections$ListN
```

### Root Cause

Java 16+ introduced `Stream.toList()` which returns an unmodifiable list (`ImmutableCollections$ListN`). SpEL's reflection mechanism in Java 25 fails to find methods on this internal class.

### Solution

**In Controller layer**, wrap the immutable list with `ArrayList`:

```java
// BAD - Returns ImmutableCollections$ListN, SpEL cannot resolve methods
List<UserQuota> usersWithQuota = allUsers.stream()
    .filter(UserQuota::quotaEnabled)
    .sorted(...)
    .toList();

// GOOD - ArrayList works correctly with SpEL
List<UserQuota> usersWithQuota = new ArrayList<>(allUsers.stream()
    .filter(UserQuota::quotaEnabled)
    .sorted(...)
    .toList());
```

### Affected Areas

- Any `List` passed to Thymeleaf templates that uses `.isEmpty()`, `.size()`, or other methods in SpEL expressions
- Particularly affects `th:if`, `th:unless` conditions

---

## SVG Gradient ID Duplication in Thymeleaf Loops

### Problem Description

When SVG `<defs>` with gradient definitions (or any elements with `id` attributes) are placed inside a `th:each` loop, multiple elements with the **same ID** are generated, violating HTML specifications.

### Error Symptoms

- Browser may fail to parse HTML correctly
- JavaScript defined later in the page may not execute
- `SidebarManager is not defined` or similar JavaScript errors

### Root Cause

```html
<!-- BAD - Gradients defined inside th:each loop -->
<div th:each="user : ${users}">
    <svg>
        <defs>
            <linearGradient id="gradientRed">...</linearGradient>  <!-- Duplicated! -->
            <linearGradient id="gradientAmber">...</linearGradient>
            <linearGradient id="gradientGreen">...</linearGradient>
        </defs>
        <circle th:attr="stroke=url(#gradientRed)"/>
    </svg>
</div>
```

When there are multiple users, each iteration generates gradients with the same ID, causing HTML validity issues.

### Solution

Move gradient definitions **outside** the loop:

```html
<!-- GOOD - Define gradients once, outside the loop -->
<svg class="hidden" aria-hidden="true">
    <defs>
        <linearGradient id="gradientRed">...</linearGradient>
        <linearGradient id="gradientAmber">...</linearGradient>
        <linearGradient id="gradientGreen">...</linearGradient>
    </defs>
</svg>

<div th:each="user : ${users}">
    <svg>
        <!-- Reference the shared gradients -->
        <circle th:attr="stroke=url(#gradientRed)"/>
    </svg>
</div>
```

### Best Practice

- Always define SVG `<defs>` (gradients, filters, patterns, etc.) **outside** of loops
- Use a hidden SVG container at the beginning of the page to hold shared definitions
- Ensure all HTML element IDs are unique across the entire document

---

## Native Image: Joda-Time Resource Missing

### Problem Description

When building a GraalVM Native Image, the application fails at runtime with a missing Joda-Time timezone resource error.

### Error Symptoms

```
Resource not found: "org/joda/time/tz/data/ZoneInfoMap"
```

### Root Cause

`jackson-datatype-joda` is transitively pulled in by Spring Cloud Function (via Spring Cloud Stream). The Joda-Time library requires timezone data files that are not automatically included in the Native Image.

### Solution

Exclude the `jackson-datatype-joda` dependency since the project uses `java.time` (not Joda-Time):

```groovy
implementation('org.springframework.cloud:spring-cloud-stream') {
    // Exclude Joda-Time Jackson module - not needed (using java.time)
    // and causes Native Image issues with missing timezone resources
    exclude group: 'com.fasterxml.jackson.datatype', module: 'jackson-datatype-joda'
}
```

### Notes

- This is safe if your project uses `java.time` API (LocalDate, ZonedDateTime, etc.)
- Spring Boot 3.x uses `jackson-datatype-jsr310` for `java.time` support by default
- Joda-Time is a legacy library superseded by `java.time` since Java 8

---

## General Best Practices

### Thymeleaf Template Guidelines

1. **Avoid calling methods on potentially immutable collections** - Use Thymeleaf utility objects or handle in Controller
2. **Keep SVG definitions outside loops** - Prevent ID duplication
3. **Test templates with various data sizes** - Empty lists, single items, multiple items

### Controller Layer Guidelines

1. **Use `ArrayList` for lists passed to templates** - Ensures SpEL compatibility
2. **Pre-compute complex expressions in Controller** - SpEL has limited support for lambdas and complex operations
3. **Handle null values in Controller** - Provide sensible defaults before passing to templates

---

*Last updated: 2025-12-21*
