# Bugs

## Open Bugs

1. **Dashboard Theme Link Should Show Theme, Module Map, And Implementation Order**

   Keep a theme link on the dashboard that lets the operator open and review the
   theme package together: the **theme** (scheme), the **module map**, and the
   **implementation order** addendum—not theme prose alone.

   Expected: from the dashboard, a single theme entry/link surfaces all three
   durable artifacts (or clearly linked sections) so the operator can inspect
   structure and delivery order without hunting under `.squad/themes/`.

2. **Implementation Order Syntax Should Use Make-Style Colons, Not The Word `after`**

   Current implementation-order lines use `dependent after provider ...`. The
   token `after` is ambiguous and could collide with a story or module id named
   `after`.

   Expected: adopt a makefile-style convention, e.g.

   ```text
   dependent: provider [provider ...]
   ```

   meaning “dependent requires these providers first” (do not start implementer
   work for `dependent` until each provider has `implementation_sha`). Update
   parser, template, analyst/SL prompts, and any recorded order files/docs to
   match. Reject or migrate the old `after` form.

3. **Implementation Order Is Theme Metadata, Not A Story**

   Implementation order must not live under `stories/` or be registered as a
   story. It is theme orchestration (sibling of the module map), recorded only
   with `squad_theme.sh implementation-order` →
   `.squad/themes/<theme-id>/implementation-order.md`.

   Expected: prompts/docs forbid `stories/` placement and `squad_theme.sh story`
   registration. (Analyst/SL prompts and template updated to say this; keep any
   leftover `stories/implementation-order.md` out of the story pipeline.)
