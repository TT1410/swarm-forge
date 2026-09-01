# Issues (lieutenant)

`get-swarm-forge` must name a product. Packs and managers are
different installs. `main` is not a product you run.

Do not pin prompt wording.

# Products

## Problem

The installer collapsed two kinds of checkout into one forge.
`get-swarm-forge` with no argument installs `main` as a multi-pack
manager. `get-swarm-forge lieutenant` installs this branch's host.
`two-pack`, `four-pack`, and `six-pack` are no longer composed into
the current directory. They only appear as templates under `packs/`
when a manager is installed.

That is the wrong split. Packs are directories you run. Managers are
forges. `main` became a manager by accident; it is the shared-script
trunk. The lieutenant *agent* on a manager is not the `lieutenant`
*branch*.

## Behavior

Five git branches. Four of them are products. `main` is not.

| Ask for | Branch | What `.` becomes |
|---|---|---|
| `two-pack` | `two-pack` | that pack, composed into `.` |
| `four-pack` | `four-pack` | that pack, composed into `.` |
| `six-pack` | `six-pack` | that pack, composed into `.` |
| `project-manager` | `project-manager` | multi-pack forge |
| `lieutenant` | `lieutenant` | single-pipeline forge |

**What is copied.** Pack-only takes `swarmforge/scripts` and the
three shared articles from `main`, never from `lieutenant` or
`project-manager`. Manager mode takes host scripts, host `swarm`,
and host `lieutenant.prompt` from **that manager branch**. It does
not fall back to `main` for those files. Shared articles on a pack
or a project-pack template are still law from `main`.

**Pack-only** (`two-pack`, `four-pack`, `six-pack`) works as it
always did. It is usually run *inside* an existing software repo.
Keep README, `bb.edn`, and project files. Compose into `.`:

- From `main`: `swarmforge/scripts` (including pack dashboard /
  `pack_web`) and `engineering.prompt` / `workflow.prompt` /
  `handoffs.prompt`.
- From the pack branch: `./swarm` (the pack launcher, not the host
  wrapper), `swarmforge/swarmforge.conf`, `constitution.prompt`,
  roles, and local articles.

`./swarm` starts that pack's agents and the pack dashboard. No
`projects/`, no `packs/`, no host `lieutenant.prompt`, no host
`swarm`, no waiting-card gate. New Task starts the card. Pack mode
does not mkdir `projects/` and does not install a host lieutenant.

**Managers** are forges. `./swarm` is the host wrapper: dashboard
and a host lieutenant, no project agents. New Project writes
`projects/<name>/`.

- `project-manager` is today's `main` forge: choosable two / four /
  six packs under `packs/`, New Project pack radios, host lieutenant
  as concierge (chat, suggest, do not cut cards). Host files from
  the `project-manager` branch.
- `lieutenant` is this branch: one pipeline at
  `.swarmforge/project-pack`, card types, host lieutenant as planner
  and dispatcher (waiting cards, start/stop, `Merge-from`). Host
  files from the `lieutenant` branch.

Do not treat `lieutenant` as a pack name. The branch carries a pack
template; asking for lieutenant still installs a manager.

**`main`** is documentary and the source of pack-only scripts and
`engineering.prompt` / `workflow.prompt` / `handoffs.prompt`. It is
not a runnable host. `get-swarm-forge main` is not a product.
Slimming `main` must not delete the pack runtime: `pack_web` and
friends still have to serve a directory with no `projects/`. If
that cockpit leaves `main`, pack-only dies.

The role named lieutenant can exist on both managers. That is a
role, not a branch. On `project-manager` it is the observer. On
`lieutenant` it is the planner.

## Verification

`get-swarm-forge` with no argument prints usage and exits nonzero.
`get-swarm-forge main` is rejected the same way.
`SWARMFORGE_BASE_BRANCH` does not resurrect a default.

`get-swarm-forge six-pack` (and two-pack, four-pack) leaves `.` as
that pack: pack `swarmforge.conf`, pack `constitution.prompt`, pack
roles, pack `./swarm`, shared articles and scripts from `main`, no
`projects/`, no `packs/`, no host `lieutenant.prompt`. `./swarm` is
the pack launcher.

`get-swarm-forge project-manager` leaves a forge with `packs/two-pack`,
`packs/four-pack`, `packs/six-pack`, empty `projects/`, host
`swarm`, and a host lieutenant prompt from that manager. No
`.swarmforge/project-pack`.

`get-swarm-forge lieutenant` leaves a forge with
`.swarmforge/project-pack`, host scripts from `lieutenant`, and no
`packs/`.

# Installer

## Problem

`get-swarm-forge` defaults to `main`, always writes `projects/`,
always requires `swarmforge/roles/lieutenant.prompt`, and treats
any unknown branch as a host. Pack names are not a compose-into-`.`
path. `SWARMFORGE_BASE_BRANCH` can still pick `main` when the
argument is missing. Local git fallback installs arbitrary refs as
hosts.

## Behavior

```text
get-swarm-forge <two-pack|four-pack|six-pack|project-manager|lieutenant>
```

No default. Unknown names fail. `main` and `master` fail.
`SWARMFORGE_BASE_BRANCH` must not supply a product when the
argument is omitted.

**Pack mode** downloads `main` (scripts and shared articles) and the
named pack branch, then composes them into `.` as above. Required
files are pack files plus shared articles and scripts. Host
lieutenant is not required and not installed.

**Manager mode** downloads that manager branch for host files (and,
for `project-manager`, the three pack branches as templates).
Required files are host files plus either `packs/*/swarmforge.conf`
or `.swarmforge/project-pack/swarmforge.conf`.

Local git fallback (`SWARMFORGE_GIT_DIR`, or `~/projects/swarm-forge`)
stays for the **five names** when GitHub lacks the branch (so
`project-manager` can be tested before it is pushed). It does not
accept some other ref as a host.

The helper on `PATH` is the installer. Keep the same script on
`main` (canonical), `project-manager`, and `lieutenant`. Copying it
onto `project-manager` copies the installer only, not this branch's
planner `lieutenant.prompt`. Pack branches may ship the helper;
they do not need a different one. A stale copy on `PATH` keeps the
old default until it is recopied from `main`.

## Verification

Help lists the five names and says packs compose into `.` and the
two managers are forges. Installer tests cover pack-only (pack
conf, pack `constitution.prompt`, pack `./swarm`, scripts and
shared articles from the `main` seed, no `projects/`, no host
lieutenant), `project-manager` (`packs/`, host lieutenant, no
project-pack), and `lieutenant` (project-pack, no `packs/`). Bare,
`main`, and an unknown ref fail. Local git fallback still works for
`lieutenant` / `project-manager` / a pack name, not for
`sf-local-only`.

# `project-manager` branch

## Problem

The runnable multi-pack forge currently lives on `main`. `main`
should not be a product.

## Behavior

Cut `project-manager` from current `origin/main`. It keeps the forge
host: dashboard, New Project pack radios, concierge lieutenant,
`packs/` install, `projects/`. It does not grow card types, waiting
cards, or a planner lieutenant. Those stay on `lieutenant`.

After the cut, `main` keeps README (as trunk docs), pack-capable
`swarmforge/scripts`, shared constitution articles, and
`get-swarm-forge`. It does not install as a forge. It still has to
boot pack-only. Manager README and getting-started live on
`project-manager` and `lieutenant`.

Helper changes that packs need go to `main` first. Pack-only uses
those. Do not make pack-only depend on `project-manager` or
`lieutenant` helpers. Those already diverge. Each manager keeps its
own scripts on its branch.

Pack README edits live on the pack branches; this checkout cannot
change them without those branches.

## Verification

`git branch project-manager` exists from `main`.
`get-swarm-forge project-manager` is the old `main` forge.
`get-swarm-forge` with no args no longer says it installed from
`main`. `get-swarm-forge six-pack` still starts a pack after `main`
is slimmed.

# Implementation plan

Do this in order. Leave each step runnable. Do not pin prompt
wording. Do not merge the two managers into one skin.

## 1. Installer menu

On this branch, change `get-swarm-forge` so the first argument is
required and must be one of `two-pack`, `four-pack`, `six-pack`,
`project-manager`, `lieutenant`. Reject `main`, `master`, empty,
and anything else. `SWARMFORGE_BASE_BRANCH` is not a default
product. Usage names the five products and the two modes (pack
into `.` vs forge).

Keep today's `lieutenant` install working while this lands:
host scripts from that branch, `.swarmforge/project-pack`, no
`packs/`. Local git fallback only for those five names.

## 2. Pack-only compose

Add a pack-mode path. For `two-pack` / `four-pack` / `six-pack`:

- Download `main` for `swarmforge/scripts` (pack cockpit included)
  and the three shared articles. Never lieutenant scripts.
- Download the pack branch for `./swarm`, `swarmforge.conf`,
  `constitution.prompt`, roles, and local articles.
- Compose into `.` in that order: scripts and shared articles from
  `main`, then pack files so pack conf / constitution / `swarm`
  win.
- Do not create `projects/` or `packs/`. Do not install host
  `lieutenant.prompt` or host `swarm`.
- Keep README, `bb.edn`, and other project files already in `.`.
- Print that a pack was installed, not a forge.

Reuse `copy_pack_template` plus the existing shared-article copy.
This is the old compose-into-`.` behavior.

Helper tests seed `main` scripts and one pack tree (env dirs, not
GitHub). Assert pack `swarmforge.conf`, pack `constitution.prompt`,
pack `./swarm`, shared articles from the `main` seed, no
`projects/`, no host lieutenant. `./swarm` is the pack launcher,
not a host wrapper.

## 3. `project-manager` name in the installer

Treat `project-manager` as manager mode with `packs/two-pack`,
`packs/four-pack`, `packs/six-pack` and no project-pack — what
no-argument `main` does today. Until step 4, the host files may
still be downloaded from `main` so the installer can be tested
before the branch exists. After step 4 they come from
`project-manager`.

Helper tests: `project-manager` writes the three packs and
`projects/`, requires host lieutenant, does not write project-pack.

## 4. Cut `project-manager` from `main`

From `origin/main`, create `project-manager`. Push it. Point the
installer at that branch for **that manager's** host files
(dashboard, concierge lieutenant prompt, forge `swarm`, its
scripts). `lieutenant` keeps taking host files from `lieutenant`.
`main` remains the source of pack-only scripts and shared articles
only.

Then slim `main`: it is not a default install. Do not remove pack
runtime from `swarmforge/scripts`. README on `main` says it is
trunk docs and shared files, and lists the five products.
Getting-started for a forge lives on `project-manager` and
`lieutenant`.

## 5. Copy the installer to `main` and `project-manager`

The PATH helper must install every product, including `lieutenant`,
without a lieutenant checkout. Same **installer script** on `main`,
`project-manager`, and `lieutenant`. Do not copy this branch's
planner `lieutenant.prompt` onto `project-manager`. Recopy
`get-swarm-forge` onto `PATH` from `main` or the old default
remains.

## 6. Docs

- `lieutenant` README: this forge, project-pack, planner lieutenant,
  `get-swarm-forge lieutenant`.
- `project-manager` README: multi-pack forge, concierge lieutenant,
  `get-swarm-forge project-manager`.
- Pack branch READMEs on those branches (not this checkout):
  pack-only, `get-swarm-forge <pack>`.
- `main` README: not a product; pointer to the five names; pack
  scripts still live here.

## 7. Tests that still assume default `main`

Replace `get-swarm-forge-default-installs-main-packs` with
project-manager. Replace `get-swarm-forge-falls-back-to-local-git-branch`
so fallback is only a named product, not `sf-local-only`. Add
pack-only as in step 2. Bare installer and `main` fail. Help lists
the five names. Do not assert prompt text.

# Out of scope

- Making lieutenant a skin on project-manager.
- Teaching a pack specifier to plan waiting cards.
- Changing two-pack / four-pack / six-pack role prompts or chains.
- Converting a pack checkout into a forge, or the reverse, in one
  command.
- Platoon / squad.
- Pinning constitution or Tool Startup wording.
