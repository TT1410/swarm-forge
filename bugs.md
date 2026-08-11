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
