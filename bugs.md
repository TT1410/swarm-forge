# Bugs

## Open Bugs

1. **Squad Leader Requests UI Should Not Expose Command/Question Kind**

   The dashboard “Squad Leader Requests” composer has a Command | Question
   segmented control. That distinction only affects a thin answer rule
   (command empty → `Done`; question requires non-empty) and is not needed
   as operator-facing UI.

   Expected: one composer + Submit (no kind toggle). Prefer a single request
   type (or drop `kind` entirely) with simple answer rules; intent comes from
   the request body. Remove or ignore the Command/Question control.

2. **Squad Leader Request History Loses Text Selection On Refresh**

   Selecting text in the Squad Leader Requests history window is cleared on
   the next dashboard poll (~2s). The panel is rebuilt with `innerHTML`, so
   the selection (and often the scroll context) is destroyed. Copy/paste from
   request or response text is difficult.

   Expected: an active selection inside the request history survives polling
   refreshes (skip rewriting that panel while text is selected, or otherwise
   preserve selection) so operators can copy without racing the 2s refresh.
