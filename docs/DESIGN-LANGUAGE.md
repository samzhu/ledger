# Ledger Dashboard Design Language

This document defines the visual design system and component patterns used across the Ledger dashboard.

## 1. Design Philosophy

- **Glassmorphism**: Primary UI style with translucent backgrounds and blur effects
- **Dark Mode First**: Full support for dark/light theme switching
- **Mobile Responsive**: Mobile-first approach with adaptive layouts
- **Micro-interactions**: Subtle hover effects and transitions for feedback

---

## 2. Color System

### Primary Palette (Indigo/Violet Gradient)
```css
/* Primary actions, active states, links */
from-indigo-500 to-violet-600    /* #6366f1 → #7c3aed */
shadow-indigo-500/25             /* Glow effect */
```

### Semantic Colors

| Purpose | Light Mode | Dark Mode | Usage |
|---------|------------|-----------|-------|
| **Success** | `emerald-600` | `emerald-400` | Cost savings, success rates ≥95%, positive status |
| **Warning** | `amber-600` | `amber-400` | Usage 70-90%, success rates 90-95% |
| **Error** | `red-600` | `red-400` | Exceeded quota, success rates <90%, errors |
| **Neutral** | `slate-600` | `slate-400` | Secondary text, disabled states |

### Gradient Icon Backgrounds

| Category | Gradient | Shadow |
|----------|----------|--------|
| Requests/Primary | `from-indigo-500 to-violet-600` | `shadow-indigo-500/25` |
| Models/Secondary | `from-violet-500 to-purple-600` | `shadow-violet-500/25` |
| Cost/Money | `from-emerald-500 to-teal-600` | `shadow-emerald-500/25` |
| Users/People | `from-amber-500 to-orange-600` | `shadow-amber-500/25` |
| Time/Latency | `from-cyan-500 to-blue-600` | `shadow-cyan-500/25` |
| Cache/Performance | `from-pink-500 to-rose-600` | `shadow-pink-500/25` |
| Danger/Warning | `from-red-500 to-rose-600` | `shadow-red-500/25` |

### Rank/Leaderboard Colors

```css
/* 1st place */ bg-gradient-to-br from-amber-400 to-yellow-500
/* 2nd place */ bg-gradient-to-br from-slate-300 to-slate-400
/* 3rd place */ bg-gradient-to-br from-orange-400 to-amber-500
/* 4th+      */ bg-slate-100 dark:bg-slate-700
```

---

## 3. Typography

### Font Stack
```css
font-family: system-ui, -apple-system, sans-serif;  /* via Tailwind defaults */
```

### Text Hierarchy

| Element | Class | Example |
|---------|-------|---------|
| Page Title | `text-xl sm:text-2xl font-bold` | "User Detail" |
| Section Title | `text-base sm:text-lg font-semibold` | "Daily Details" |
| Card Label | `text-xs sm:text-sm font-medium text-slate-500 dark:text-slate-400` | "Total Requests" |
| Metric Value | `text-xl sm:text-2xl font-bold` | "1,234" |
| Table Header | `text-xs font-medium uppercase tracking-wider` | "REQUESTS" |
| Body Text | `text-sm text-slate-600 dark:text-slate-300` | Table cell content |
| Secondary Text | `text-xs text-slate-400 dark:text-slate-500` | Timestamps, hints |

### Cost Display
```html
<!-- Primary cost display -->
<p class="text-xl sm:text-2xl font-bold text-emerald-600 dark:text-emerald-400">
    $<span>0.00</span>
</p>

<!-- Secondary/table cost -->
<span class="text-sm text-emerald-600 dark:text-emerald-400 font-semibold">$0.00</span>
```

---

## 4. Component Patterns

### 4.1 Glass Card (Primary Container)

```html
<div class="glass-card rounded-2xl p-4 sm:p-6">
    <!-- content -->
</div>
```

CSS Definition:
```css
.glass-card {
    background: rgba(255, 255, 255, 0.75);      /* Light mode */
    backdrop-filter: blur(16px) saturate(180%);
    border: 1px solid rgba(255, 255, 255, 0.4);
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.08);
}

.dark .glass-card {
    background: rgba(15, 23, 42, 0.85);         /* Dark mode */
    border: 1px solid rgba(255, 255, 255, 0.08);
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.25);
}
```

### 4.2 Stat Card (Metric Display)

```html
<div class="glass-card stat-card rounded-2xl p-4 sm:p-5 group">
    <div class="flex items-center justify-between mb-3">
        <!-- Label -->
        <p class="text-slate-500 dark:text-slate-400 text-xs sm:text-sm font-medium">
            Total Requests
        </p>
        <!-- Icon Container -->
        <div class="w-9 h-9 sm:w-10 sm:h-10 bg-gradient-to-br from-indigo-500 to-violet-600
                    rounded-xl flex items-center justify-center
                    shadow-lg shadow-indigo-500/25
                    group-hover:scale-110 transition-transform">
            <svg class="w-4 h-4 sm:w-5 sm:h-5 text-white">...</svg>
        </div>
    </div>
    <!-- Value -->
    <p class="text-xl sm:text-2xl font-bold text-slate-800 dark:text-white">1,234</p>
    <!-- Optional subtitle -->
    <p class="text-xs text-slate-400 dark:text-slate-500 mt-1">+5% from yesterday</p>
</div>
```

Hover Effect:
```css
.stat-card {
    transition: transform 200ms ease, box-shadow 200ms ease;
}
.stat-card:hover {
    transform: translateY(-2px);
}
.dark .stat-card:hover {
    box-shadow: 0 12px 40px rgba(99, 102, 241, 0.15);
}
```

### 4.3 Status Badge

```html
<!-- Success/Active -->
<span class="inline-flex items-center px-2.5 py-1 rounded-full text-xs font-medium
             bg-emerald-100 dark:bg-emerald-500/20
             text-emerald-700 dark:text-emerald-400">
    <svg class="w-3 h-3 mr-1">...</svg>
    Active
</span>

<!-- Warning -->
<span class="inline-flex items-center px-2.5 py-1 rounded-full text-xs font-medium
             bg-amber-100 dark:bg-amber-500/20
             text-amber-700 dark:text-amber-400">
    Warning
</span>

<!-- Error/Exceeded -->
<span class="inline-flex items-center px-2.5 py-1 rounded-full text-xs font-medium
             bg-red-100 dark:bg-red-500/20
             text-red-700 dark:text-red-400">
    <svg class="w-3 h-3 mr-1">...</svg>
    Exceeded
</span>

<!-- Neutral -->
<span class="inline-flex items-center px-2.5 py-1 rounded-full text-xs font-medium
             bg-slate-100 dark:bg-slate-500/20
             text-slate-600 dark:text-slate-400">
    No Quota
</span>
```

### 4.4 Progress Bar

```html
<div class="w-full bg-slate-200 dark:bg-slate-700 rounded-full h-2 overflow-hidden">
    <div class="h-2 rounded-full transition-all duration-500"
         th:classappend="${percent >= 90} ? 'bg-gradient-to-r from-red-500 to-rose-500'
                       : (${percent >= 70} ? 'bg-gradient-to-r from-amber-500 to-orange-500'
                       : 'bg-gradient-to-r from-emerald-500 to-teal-500')"
         th:style="'width: ' + ${percent} + '%'">
    </div>
</div>
```

### 4.5 Ring Chart (Circular Progress)

```html
<div class="relative inline-flex items-center justify-center">
    <svg class="w-24 h-24 transform -rotate-90">
        <!-- Background circle -->
        <circle cx="50%" cy="50%" r="40%"
                class="stroke-slate-200 dark:stroke-slate-700"
                stroke-width="8" fill="none"/>
        <!-- Progress circle -->
        <circle cx="50%" cy="50%" r="40%"
                stroke="url(#gradientGreen)"
                stroke-width="8" fill="none"
                stroke-linecap="round"
                class="drop-shadow-lg"
                style="stroke-dasharray: 251; stroke-dashoffset: 125"/>
        <defs>
            <linearGradient id="gradientGreen" x1="0%" y1="0%" x2="100%" y2="0%">
                <stop offset="0%" stop-color="#10b981"/>
                <stop offset="100%" stop-color="#34d399"/>
            </linearGradient>
        </defs>
    </svg>
    <!-- Center text -->
    <span class="absolute text-lg font-bold text-slate-800 dark:text-white">50%</span>
</div>
```

### 4.6 Data Table

```html
<div class="glass-card rounded-2xl overflow-hidden">
    <!-- Header -->
    <div class="px-4 sm:px-6 py-4 border-b border-slate-200/50 dark:border-slate-700/50
                bg-gradient-to-r from-slate-50/50 dark:from-slate-800/50 to-transparent">
        <h2 class="text-base sm:text-lg font-semibold text-slate-800 dark:text-white">
            All Users
        </h2>
    </div>

    <!-- Mobile Card View -->
    <div class="lg:hidden divide-y divide-slate-100 dark:divide-slate-700/50">
        <!-- Card items for mobile -->
    </div>

    <!-- Desktop Table View -->
    <div class="hidden lg:block overflow-x-auto">
        <table class="w-full min-w-[800px]">
            <thead class="bg-slate-50/50 dark:bg-slate-800/50">
                <tr>
                    <th class="px-6 py-3 text-left text-xs font-medium
                               text-slate-500 dark:text-slate-400 uppercase tracking-wider">
                        Column
                    </th>
                </tr>
            </thead>
            <tbody class="divide-y divide-slate-100 dark:divide-slate-700/50">
                <tr class="hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors">
                    <td class="px-6 py-4 text-sm text-slate-600 dark:text-slate-300">
                        Content
                    </td>
                </tr>
            </tbody>
        </table>
    </div>
</div>
```

### 4.7 Date Range Selector

```html
<div class="flex flex-wrap gap-2">
    <!-- Active state -->
    <a class="bg-gradient-to-r from-indigo-500 to-violet-600 text-white
              shadow-lg shadow-indigo-500/25
              px-3 sm:px-4 py-2 rounded-lg text-sm font-medium transition-all">
        7 Days
    </a>
    <!-- Inactive state -->
    <a class="glass-card text-slate-700 dark:text-slate-300
              hover:bg-white/80 dark:hover:bg-slate-800/80
              px-3 sm:px-4 py-2 rounded-lg text-sm font-medium transition-all">
        14 Days
    </a>
</div>
```

### 4.8 Modal Dialog

```html
<div class="fixed inset-0 bg-black/60 backdrop-blur-sm z-50
            flex items-center justify-center p-4">
    <div class="glass-card rounded-2xl shadow-2xl w-full max-w-md
                max-h-[90vh] overflow-y-auto">
        <!-- Header (sticky) -->
        <div class="px-4 sm:px-6 py-4 border-b border-slate-200/50 dark:border-slate-700/50
                    flex justify-between items-center sticky top-0
                    bg-white/80 dark:bg-slate-900/80 backdrop-blur-sm rounded-t-2xl">
            <h3 class="text-base sm:text-lg font-semibold text-slate-800 dark:text-white">
                Title
            </h3>
            <button class="text-slate-400 hover:text-slate-600 dark:hover:text-slate-300
                           p-1 transition-colors">
                <svg class="w-5 h-5"><!-- X icon --></svg>
            </button>
        </div>
        <!-- Body -->
        <div class="p-4 sm:p-6">
            <!-- Form content -->
        </div>
    </div>
</div>
```

### 4.9 Button Styles

```html
<!-- Primary (Gradient) -->
<button class="px-4 py-2 text-sm font-medium text-white
               bg-gradient-to-r from-indigo-500 to-violet-600
               hover:from-indigo-600 hover:to-violet-700
               rounded-lg shadow-lg shadow-indigo-500/25 transition">
    Save
</button>

<!-- Secondary -->
<button class="px-4 py-2 text-sm font-medium
               text-slate-700 dark:text-slate-300
               bg-slate-100 dark:bg-slate-700
               hover:bg-slate-200 dark:hover:bg-slate-600
               rounded-lg transition">
    Cancel
</button>

<!-- Outlined (Action Button) -->
<button class="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg
               text-xs sm:text-sm font-medium
               bg-indigo-50 dark:bg-indigo-500/20
               text-indigo-700 dark:text-indigo-300
               border border-indigo-200 dark:border-indigo-500/30
               hover:bg-indigo-100 dark:hover:bg-indigo-500/30 transition">
    <svg class="w-4 h-4">...</svg>
    Configure
</button>
```

### 4.10 Empty State

```html
<div class="p-8 text-center">
    <div class="w-16 h-16 bg-gradient-to-br from-slate-100 to-slate-200
                dark:from-slate-700 dark:to-slate-800
                rounded-2xl flex items-center justify-center mx-auto mb-4">
        <svg class="w-8 h-8 text-slate-400 dark:text-slate-500">...</svg>
    </div>
    <p class="text-slate-500 dark:text-slate-400 text-sm">No data available</p>
    <p class="text-slate-400 dark:text-slate-500 text-xs mt-1">
        Data will appear here once available
    </p>
</div>
```

### 4.11 Breadcrumb Navigation

```html
<nav class="flex items-center gap-2 text-sm text-slate-500 dark:text-slate-400 mb-2">
    <a href="#" class="hover:text-indigo-600 dark:hover:text-indigo-400 transition-colors">
        Users
    </a>
    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7"/>
    </svg>
    <span class="text-slate-700 dark:text-slate-300">user-id</span>
</nav>
```

---

## 5. Spacing System

| Size | Tailwind | Usage |
|------|----------|-------|
| xs | `gap-2`, `p-2` | Compact spacing within components |
| sm | `gap-3`, `p-3` | Internal component padding |
| md | `gap-4`, `p-4` | Card padding (mobile), grid gaps |
| lg | `gap-6`, `p-6` | Card padding (desktop), section gaps |
| xl | `mb-6` | Section margins |

### Responsive Pattern
```css
/* Padding: tighter on mobile, relaxed on desktop */
p-4 sm:p-5 lg:p-6

/* Gap: tighter on mobile */
gap-3 sm:gap-4

/* Grid columns: stack on mobile, expand on larger screens */
grid-cols-1 sm:grid-cols-2 lg:grid-cols-4
```

---

## 6. Responsive Breakpoints

| Breakpoint | Width | Usage |
|------------|-------|-------|
| (default) | < 640px | Mobile - single column, card views |
| `sm:` | ≥ 640px | Small tablets - 2 columns, larger text |
| `md:` | ≥ 768px | Tablets - expanded layouts |
| `lg:` | ≥ 1024px | Desktop - table views, sidebar visible |
| `xl:` | ≥ 1280px | Large desktop - 4+ columns |

### Common Responsive Patterns

```html
<!-- Mobile cards → Desktop table -->
<div class="lg:hidden"><!-- Mobile card view --></div>
<div class="hidden lg:block"><!-- Desktop table --></div>

<!-- Responsive grid -->
<div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4">

<!-- Responsive text -->
<p class="text-xs sm:text-sm">Label</p>
<p class="text-xl sm:text-2xl font-bold">Value</p>

<!-- Hide on mobile -->
<span class="hidden sm:inline">Full text</span>
```

---

## 7. Icons

- **Source**: Heroicons (outline style)
- **Sizes**: `w-4 h-4` (small), `w-5 h-5` (medium), `w-6 h-6` (large)
- **Color**: `text-white` in gradient containers, `text-slate-*` elsewhere

Common icons used:
- Lightning bolt: Requests/API calls
- Tag: Tokens
- Currency/Dollar: Cost
- Users: People/Accounts
- Clock: Time/Latency
- Shield: Quota/Security
- Chart: Analytics
- Computer: Models

---

## 8. Animation & Transitions

```css
/* Standard transition */
transition-all duration-200

/* Hover scale (icons) */
group-hover:scale-110 transition-transform

/* Progress bar animation */
transition-all duration-500

/* Sidebar transition */
transition: width 300ms cubic-bezier(0.4, 0, 0.2, 1);
```

---

## 9. Chart.js Configuration

### Color Constants
```javascript
const modelColors = ['#6366f1', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#ec4899'];
const isDarkMode = document.documentElement.classList.contains('dark');
const textColor = isDarkMode ? '#94a3b8' : '#64748b';
const gridColor = isDarkMode ? 'rgba(148, 163, 184, 0.1)' : 'rgba(0, 0, 0, 0.05)';
```

### Standard Options
```javascript
{
    responsive: true,
    maintainAspectRatio: false,
    plugins: {
        legend: {
            position: 'top',
            labels: { color: textColor }
        }
    },
    scales: {
        y: {
            beginAtZero: true,
            ticks: { color: textColor },
            grid: { color: gridColor }
        },
        x: {
            ticks: { color: textColor },
            grid: { color: gridColor }
        }
    }
}
```

---

## 10. SVG Gradient Definitions

Place these once at the top of the page when using ring charts:

```html
<svg class="absolute w-0 h-0" aria-hidden="true">
    <defs>
        <linearGradient id="gradientRed" x1="0%" y1="0%" x2="100%" y2="0%">
            <stop offset="0%" stop-color="#ef4444"/>
            <stop offset="100%" stop-color="#f87171"/>
        </linearGradient>
        <linearGradient id="gradientAmber" x1="0%" y1="0%" x2="100%" y2="0%">
            <stop offset="0%" stop-color="#f59e0b"/>
            <stop offset="100%" stop-color="#fbbf24"/>
        </linearGradient>
        <linearGradient id="gradientGreen" x1="0%" y1="0%" x2="100%" y2="0%">
            <stop offset="0%" stop-color="#10b981"/>
            <stop offset="100%" stop-color="#34d399"/>
        </linearGradient>
    </defs>
</svg>
```

---

## 11. Dark Mode Implementation

### Toggle Function
```javascript
const ThemeManager = {
    STORAGE_KEY: 'ledger-theme',
    toggle() {
        const isDark = document.documentElement.classList.toggle('dark');
        localStorage.setItem(this.STORAGE_KEY, isDark ? 'dark' : 'light');
    }
};
```

### Initial Load (prevent flash)
```html
<script>
    (function() {
        const savedTheme = localStorage.getItem('ledger-theme');
        const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
        if (savedTheme === 'dark' || (!savedTheme && prefersDark)) {
            document.documentElement.classList.add('dark');
        }
    })();
</script>
```

---

## 12. Thymeleaf Integration Patterns

### Conditional Styling
```html
th:classappend="${value >= 90} ? 'text-red-600' : 'text-emerald-600'"
```

### Number Formatting
```html
th:text="${#numbers.formatInteger(count, 0, 'COMMA')}"      <!-- 1,234 -->
th:text="${#numbers.formatDecimal(cost, 1, 2)}"             <!-- 12.34 -->
```

### UTC to Local Time
```html
<span th:data-utc="${timestamp}" data-format="YYYY-MM-DD HH:mm">-</span>
```

---

## Summary

This design system provides:
- **Consistent visual language** across all dashboard pages
- **Glassmorphism aesthetics** with proper dark mode support
- **Responsive patterns** for mobile-first development
- **Reusable components** for rapid UI development
- **Semantic color system** for data visualization
