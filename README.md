# SwarmForge four-pack

Pack-only workflow: specifier → coder → refactorer → architect.

`main` is documentary and the source of shared scripts. `project-manager` and
`lieutenant` are forges. This branch is the pack.

## Getting Started

Install `get-swarm-forge` on your `PATH` from the swarm-forge repo, then in the
project directory:

```sh
get-swarm-forge four-pack
./swarm
```

That composes this pack into `.` (scripts and shared articles from `main`,
conf and roles from this branch). It is not a forge. Use `two-pack` or
`six-pack` for those workflows. For the multi-pack dashboard use
`get-swarm-forge project-manager`. For the lieutenant host use
`get-swarm-forge lieutenant`.
