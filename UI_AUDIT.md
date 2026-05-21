## UI Audit Report — 2026-05-21

### Score: 6.9 / 10

| Category           | Score | Notes |
|--------------------|-------|-------|
| Visual Hierarchy   |  7/10 | Good focal points (dashboard inspiration bar, project cards). Some views use generic `page-title` with emoji prefixes (CharacterView "👤 角色管理") that feel unpolished compared to the dashboard's refined typography. |
| Typography         |  7/10 | Consistent Inter + PingFang SC stack. `tracking-tight` on headings is good. Body line-height varies (1.5 vs 1.55 vs 1.65) across views. Small text (12px) used extensively in cards — borderline readability. |
| Depth & Layering   |  8/10 | Three-tier shadow system (`sm/md/lg`) well-applied. Dashboard glassmorphism (backdrop-blur on inspiration bar) is excellent. Card hover elevations are consistent. `primary-glow` shadow on CTAs adds nice depth. |
| Interactive States |  6/10 | Hover states mostly present. Missing `:active` states on custom buttons (`.send-round`, `.btn-confirm-outline`, `.episode-dot`). `:focus-visible` only on `.rail-user` — all other custom buttons lack it. Disabled opacity inconsistent (0.4 vs 0.45 vs 0.55). |
| Responsiveness     |  7/10 | Project grid has excellent responsive columns (2→3→4→5→6→7→8). Settings tabs collapse properly. Immersive view's episode rail (56px fixed) and plan panel don't adapt well below 768px. No mobile sidebar collapse. |
| Accessibility      |  5/10 | `aria-label` on sidebar nav and episode rail. Missing on: `.composer-collapse`, `.attach-btn`, `.send-round`, `.episode-add`, `.tool-ico` buttons. Color contrast: `--text-muted` (#9ca3af) on `--bg-page` (#f9fafb) = 2.8:1 ratio (fails WCAG AA). No skip-nav link. |
| Motion             |  7/10 | Dashboard expand/collapse transition is purposeful and well-timed (0.55s cubic-bezier). Card hover transforms (translateY -2px) are consistent. `@keyframes spin` for loading. Missing `prefers-reduced-motion` guards on all animations. |

---

### Top 3 improvements (highest ROI first):

#### 1. **Interactive States** — Add `:active`, `:focus-visible`, and consistent `:disabled` to all custom buttons

Many custom buttons (`.send-round`, `.btn-confirm-outline`, `.episode-dot`, `.quick-chip`, `.inspire-submit`) only have `:hover`. Missing `:active` gives no press feedback, and missing `:focus-visible` breaks keyboard navigation.

**Before** (`ImmersiveCreateView.vue` — `.send-round`):
```css
.send-round:hover:not(:disabled) {
  transform: scale(1.05);
}
.send-round:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
```

**After**:
```css
.send-round:hover:not(:disabled) {
  transform: scale(1.05);
}
.send-round:active:not(:disabled) {
  transform: scale(0.96);
}
.send-round:focus-visible {
  outline: 2px solid var(--primary-light);
  outline-offset: 2px;
}
.send-round:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
```

Apply the same pattern to `.btn-confirm-outline`, `.episode-dot`, `.quick-chip`, `.inspire-submit`, `.rail-icon`, `.rail-menu-btn`, `.config-tab`, `.proj-card-del`, `.btn-plan-video`, `.btn-plan-prompts`, `.btn-ep-primary`, `.btn-ep-cancel`.

Standardize disabled opacity to `0.4` across all buttons.

---

#### 2. **Accessibility** — Add `aria-label` to icon-only buttons and fix color contrast

Multiple buttons have no accessible label. Screen readers announce them as "button" with no context.

**Before** (`DashboardView.vue`):
```html
<button type="button" class="inspire-submit" title="开始创作" @click.stop="goFromInspiration">
```
```html
<button type="button" class="composer-collapse" title="收起" aria-label="收起" @click="collapseComposer">
```
```html
<button type="button" class="tool-ico" title="附件">
```

**After**:
```html
<button type="button" class="inspire-submit" aria-label="开始创作" @click.stop="goFromInspiration">
```
```html
<button type="button" class="composer-collapse" aria-label="收起" @click="collapseComposer">
```
```html
<button type="button" class="tool-ico" aria-label="附件">
```

Add `aria-label` to all icon-only buttons: `.attach-btn`, `.send-round`, `.episode-add`, `.tool-ico`, `.rail-menu-btn`, `.icon-btn` in ImmersiveCreateView.

Fix `--text-muted` contrast: change from `#9ca3af` to `#7c8495` (light mode) for 4.5:1 ratio against `--bg-page`.

---

#### 3. **Hardcoded Colors** — Replace raw hex values with CSS custom properties

Several views bypass the token system with hardcoded colors, breaking dark mode and theme consistency.

**Before** (`LoginView.vue`):
```css
:deep(.el-input__wrapper) {
  background: #fff;
  box-shadow: 0 0 0 1px rgba(17, 24, 39, 0.12) inset;
}
.primary-btn {
  background: #111827;
}
.primary-btn:hover {
  background: #09090b;
}
```

**After**:
```css
:deep(.el-input__wrapper) {
  background: var(--bg-card);
  box-shadow: 0 0 0 1px var(--border) inset;
}
.primary-btn {
  background: var(--text-primary);
}
.primary-btn:hover {
  background: var(--primary-dark);
}
```

**Before** (`ImmersiveCreateView.vue`):
```css
.vip-link {
  color: #e8c785;
}
.image-debug-err {
  color: #fca5a5;
}
.image-debug-desc strong {
  color: #c7d2fe;
}
```

**After**:
```css
.vip-link {
  color: var(--color-warning);
}
.image-debug-err {
  color: var(--color-danger);
}
.image-debug-desc strong {
  color: var(--primary-light);
}
```

Also fix `.mode-pill-new` (`#fde047`, `#facc15`, `#0f172a`) — define `--accent-yellow` and `--accent-yellow-text` tokens.
