# SL Request Implementation Plan

## Decisions (locked)

1. **Priority (residual `squad_next` order)**  
   After required mechanical bookkeeping only:
   - finish in-process handoff step  
   - claim/process new handoff  
   - clear stale spawn lock  
   - wait for in-flight spawn  
   then **`answer_dashboard_request` (FIFO oldest pending)**  
   then retire / recover / story ready-actions / request_user_approval / wait.

2. **State machine (minimal)**  
   - `pending` → `answered` (success for command or question)  
   - `pending` → `rejected` (SL cannot/will not, or operator cancel)  
   - CLI: `answer` (alias `complete`) and `reject`  
   - Folders: `pending/`, `answered/`, `rejected/` under `.swarmforge/dashboard/requests/`

3. **Queue**  
   Multiple pending allowed. SL answers **FIFO** by `created_at` / id order.  
   `squad_next` always surfaces the oldest pending request.

4. **Operator cancel**  
   Supported: moves pending → `rejected` with detail `cancelled-by-operator`  
   (dashboard control + optional helper).

5. **Response rules**  
   - Command: non-empty response preferred; blank normalizes to `Done`.  
   - Question: non-empty answer required.  
   - Request body: trim; reject empty/whitespace; max **8000** characters.

6. **History UI**  
   Show up to **50** most recent requests (all states), oldest→newest in the scroll area.  
   Auto-scroll only when already near bottom. Files retained on disk beyond the UI cap.

7. **Migration**  
   `POST /api/sl-message` becomes a thin wrapper that creates a **command** request  
   (same durable path). Single composer in the UI; no dual UX.

8. **Watchdog**  
   Pending requests keep the SL nudge path active (do not suppress like pending  
   *story* approvals). Throttle nudges (reuse existing SL watchdog cooldown).  
   Wake text always includes `REQUEST_ID` and the exact helper command shape.

## Implement order

1. Durable request library + `squad_dashboard_request.sh` (`answer` / `complete` / `reject` / `cancel` / `status`)
2. Web: create/list/cancel APIs, include in `/api/state`, wake SL with command shape
3. `squad_next`: `answer_dashboard_request` at locked priority
4. Dashboard UI: history + composer + Command|Question
5. SL prompt + watchdog reliability
6. Tests

## Original design notes

### Durable layout

```
.swarmforge/dashboard/requests/
  pending/<id>.request
  answered/<id>.request
  rejected/<id>.request
```

Fields: `id`, `kind` (`command`|`question`), `status`, `created_at`, `updated_at`,
`answered_at` (when set), `body`, `response` (when set), `detail` (reject/cancel reason).

### API

- `POST /api/sl-requests` — create (JSON `kind` + `body`), return id, wake SL  
- `GET /api/sl-requests` — recent pending/answered/rejected  
- `POST /api/sl-requests/<id>/cancel` — operator cancel  
- `POST /api/sl-message` — wrapper → command request  

### Helper

```sh
squad_dashboard_request.sh answer <id> <answer-file>
squad_dashboard_request.sh complete <id> <answer-file>   # alias
squad_dashboard_request.sh reject <id> <reason-file>
squad_dashboard_request.sh cancel <id>                   # operator cancel
squad_dashboard_request.sh status [id]
```

### `squad_next` residual

```text
NEXT_ACTION: answer_dashboard_request
REQUEST_ID: dashboard-...
KIND: command|question
COMMAND: squad_dashboard_request.sh answer <id> <answer-file>
```

### UI

- Section title: **Squad Leader Requests**  
- Scrollable history, composer, Command|Question (default Command), Submit  
- You vs SL styling; status pills; near-bottom auto-scroll  

### Tests

- Post creates durable state and wakes SL  
- Empty rejected; path/id safety  
- `squad_next` formal action  
- Helper resolves only correct id  
- History order + styling hooks  
- Pane text alone does not complete a request  
