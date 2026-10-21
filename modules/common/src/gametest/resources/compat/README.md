# Undo history samples (frozen)

Real history journals (`history/<player uuid>.bshist`) written by released Sculptory builds, one folder per version.
`CompatHistoryGameTest` puts each in a history folder, lays the world out as that build left it, and checks that redo
and undo of every step restore the world exactly with the current code, so a build that would lose a player's undo
history after an update fails `check`.

**Never edit these files.** A change to the journal format adds a new sample set (a new version folder, written by
that build) and a migration that reads the older sets, with tests; the older samples stay exactly as they are.

Each journal was written by that version's own edit service and history store on a GameTest world (a throwaway test
run from a checkout of that version), at x -1600000, z 1600000, by player `3f2a9c1e-7b4d-4e8a-9c3b-5d6e7f809a1b`:
over grass, a sign, a chest of diamonds and logs and a stone pedestal under a named armor stand (on cherry grove in
0.4.0), it filled glass over the sign and chest, (0.4.0 only: filled desert biome next to it), filled gold over part of
the glass, moved the pedestal and the stand 8 blocks north, then undid the move and the gold. So the undo list holds
the glass (and the biome fill) and the redo list the gold and the move. Minecraft 1.21.1 (data version 3955); steps of
another data version are dropped by design.
