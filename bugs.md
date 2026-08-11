# Bugs

## Open Bugs

1. **Agent Pane Scroll Position Jitters When New Output Arrives**

   When an agent pane is open (click agent → pane window) and the operator has
   scrolled in that view, the scroll position jitters when new text is appended
   at the end. Reading mid-history becomes unstable as output grows.

   Expected: if the user is not pinned to the bottom, keep a stable scroll
   anchor (no jump/jitter when content is prepended or length changes). Only
   auto-scroll to the bottom when the user was already near the bottom (or
   chooses “New output” / stick-to-bottom).
