# Design System

## Stack
- framework: Vue 3.5 + TypeScript
- build: Vite 8
- components: Element Plus 2.13 + @element-plus/icons-vue
- styling: CSS custom properties (no Tailwind)
- state: Pinia 3
- routing: Vue Router 5
- animation: CSS transitions (no Framer Motion)
- icons: @element-plus/icons-vue

## Tokens
Defined in `frontend/src/style.css` with light/dark mode support.

- brand: `--primary: #6366f1` / dark: `#818cf8`
- secondary: `--secondary: #ec4899`
- accent: `--accent: #06b6d4`
- bg-page: `#f9fafb` / dark: `#0a0a0f`
- bg-card: `#ffffff` / dark: `#14141e`
- text-primary: `#111827` / dark: `#f3f4f6`
- text-secondary: `#6b7280` / dark: `#9ca3af`
- border: `#e8ecf4` / dark: `rgba(255,255,255,0.08)`
- radius: sm=8px, md=12px, lg=16px, xl=24px, full=9999px
- shadow: sm/md/lg layered + primary glow
- status: success=#10b981, warning=#f59e0b, danger=#ef4444, info=#3b82f6
- sidebar: `--sidebar-bg: #0f1020`, width=224px

## Decisions
- 2026-05-21 — init: Vue 3 + Vite + Element Plus detected. Custom CSS variables preset (no Tailwind). Existing token system in `style.css` adopted as source of truth.
- 2026-05-21 — audit: Score 6.9/10. Top issues: (1) missing `:active`/`:focus-visible` on custom buttons, (2) icon-only buttons lack `aria-label`, (3) hardcoded hex colors bypass token system. Strengths: shadow system, dashboard glassmorphism, responsive grid.
- 2026-05-21 — dribbble fixes: page-title 22→36px, feature cards +icons +descriptions, login btn gradient primary, hover translateY(-4px), global spacing increase.
- 2026-05-21 — dark mode: activated `.dark` class on `<html>`, login/dashboard use `background1.png` with overlay, auth-card glass effect, status badges dark-adapted, scrollbar dark-adapted.

## Components
(none yet — Element Plus provides base components)

## Non-Goals
- No Tailwind migration
- No Figma sync
- No image generation
