# Save states

Save the whole running game at any moment and go back to it later, like an emulator's save
states: mid-battle, mid-scene, in a menu. They are separate from the game's own saves.

## Using them

| | Thor buttons | Launcher |
|---|---|---|
| Save the quick state | **Select + L1** | |
| Load the quick state | **Select + R1** | |
| Start the game at the quick state | | Settings > Start > *Resume the save state* |

- A note on screen says `Saving state...`, then `State saved (26 MB, 0.1 s)`; `State loaded`.
- Saving pauses the game for about a tenth of a second; the file is written in the
  background. Loading takes effect at the next frame the game can be swapped at -- usually the
  next one; during a loading screen or transition it waits for the game's own frame loop (up
  to 10 s) and then says why it could not.
- *Resume the save state* loads it about 1.5 s after the app starts (no credits, title or
  opening). Without a state it starts a new game instead.
- The game does not see Select+L1 / Select+R1 (they are taken for save states).
- Saving is refused while a movie plays and between scenes (`Cannot save now: ...`).

Files: `files/savestates/quick.ff7s` (about 3 MB, deflated), the previous one kept as
`quick.ff7s.bak`. Save states are game data -- personal and copyrighted like the game itself;
never share or commit them.

Scripts and tests: `bash tools/ss_cmd.sh save|load [slot]` asks the running game (any slot
name: `save battle`, `load battle`); `FF7_QUICKBOOT=resume` in env.txt is the launcher option.

## What has been checked (on the Thor)

- Same scene: save, play on, load -- the scene rewinds and plays on.
- Another launch: save, restart the app, load -- 39 texture sets rebuilt, picture right.
- Another mode: load a field state from the opening credits -- field, widescreen layout and
  field music come back (music 95 -> 2).
- Battle: save mid-battle, win a turn, load -- both enemies back, the battle plays on with
  its effects and damage numbers.

## How it works

`src/host/ff7_savestate.c`; the FFNx side in `src/ffnx_port/savestate_ffnx.cpp`.

- **When**: at the driver's flip (`common_flip`), once per frame after the mode's loop has
  returned, so no game-mode code is on the host stack. A state records the host stack depth
  and the guest `esp` there and is only applied where both match (the same call chain), which
  is what lets it load across modes and launches.
- **What**: the guest registers, flags and x87 stack; the exe's .data/.bss, FFNx's static
  block, every allocated range of the guest heap (the guest stack is one) and the VirtualAlloc
  regions; and the host-side bookkeeping that memory depends on: the heap's free list
  (pcrecomp patch 0020), the VirtualAlloc table, the DirectSound voices, the open file handles
  (path, mode, position: reopened on load), the guest working directory.
- **FFNx** (hooks): texture sets live in guest memory but carry host state (GPU handles,
  std::maps of host nodes). Every set is registered when FFNx creates it; a state lists them;
  a load deletes the running game's GPU textures first, then gives every restored set fresh
  maps and uploads each palette it had again from the image data in guest memory
  (`common_load_texture`). The music track and FFNx's per-field caches and widescreen layout
  (set on scene entry, which a load skips) are put back too.
- **Format**: a header (version, build, time, mode) and deflated tagged records. A state from
  another build loads with a note; one whose layout differs is refused before anything is
  touched.

## Not done yet

- One quick slot (+ named slots through `ss_cmd.sh`); no slot menu, thumbnails or undo.
- Checked in fields and battles; world map, menus and the minigames are untested.
- Mid-effect battle states: FFNx keeps per-effect interpolation state on the host side; a load
  in the middle of a spell may show its first frames oddly.
