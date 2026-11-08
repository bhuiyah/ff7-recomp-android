# Save states

Save the whole running game at any moment and go back to it later, like an emulator's save
states: mid-battle, mid-scene, in a menu. They are separate from the game's own saves.

## Using them

There are 10 slots. The in-game buttons use the *current* slot.

| | Thor buttons | Launcher |
|---|---|---|
| Save to the current slot | **Select + L1** | |
| Load the current slot | **Select + R1** | |
| Pick the previous / next slot | **Select + Left / Right** | *Save states* > Make current |
| Start the game at a slot | | *Save states* > Play from here |
| Start every launch at the current slot | | Settings > Start > *Resume the save state* |
| Delete a slot | | *Save states* > Delete |

- Picking a slot shows what it holds, e.g. `Slot 2: md1stin - 0:00:07 - saved 08 Nov 00:54`
  (where, the game's play time, when), or `Slot 3: empty`.
- Saving shows `Saving state...`, then `State saved (31 MB, 0.1 s)`. Loading shows `State loaded`.
- Saving pauses the game for about a tenth of a second, and the file is written in the
  background.
- A load takes effect at the next frame where the game can be swapped, which is usually the
  next frame. During a loading screen or transition it waits up to 10 s for the game's own
  frame loop. If it still cannot load, it says why.
- *Play from here* and *Resume* load the slot about 1.5 s after the app starts, which skips
  the credits, title and opening. If the slot is empty, a new game starts instead.
- The launcher's *Save states* page lists every slot with its location, play time and save
  date.
- The game does not see Select+L1 / R1 / Left / Right. Those combinations are reserved for
  save states.
- Saving is refused while a movie plays and between scenes (`Cannot save now: ...`).

Files:
- Slots are `files/savestates/slot01.ff7s` .. `slot10.ff7s`, about 3 MB each (deflated).
- The previous version of a slot is kept as `slotNN.ff7s.bak`.
- `current.txt` holds the current slot number.
- A `quick.ff7s` from before slots existed becomes slot 1.
- Save states are game data, as personal and copyrighted as the game itself: never share or
  commit them.

Scripts and tests:
- `bash tools/ss_cmd.sh save|load [slot]` asks the running game to save or load. The slot can
  be a number, `current`, or any name (`save battle`).
- `FF7_QUICKBOOT=resume [N]` in env.txt does the same as the launcher's start options.

## What has been checked (on the Thor)

- Same scene: save, play on, load -- the scene rewinds and plays on.
- Another launch: save, restart the app, load -- 39 texture sets rebuilt, picture right.
- Another mode: load a field state from the opening credits -- field, widescreen layout and
  field music come back (music 95 -> 2).
- Battle: save mid-battle, win a turn, then load. Both enemies come back, and the battle plays
  on with its effects and damage numbers.
- Slots:
  - The launcher page lists them (an old v1 slot shows as "from an older version").
  - *Play from here* opened slot 2 in 16:9.
  - Select+Right moved to slot 2 and showed its note.
  - Select+L1 saved there, and Select+R1 loaded it.

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
- **Format**: a header and deflated tagged records. The header (version 2, 216 bytes) holds the
  version, build, time, mode, label, location and play time. The launcher reads it in
  `SaveStates.java`. A state from
  another build loads with a note; one whose layout differs is refused before anything is
  touched.

## Not done yet

- No thumbnails or undo.
- The location is the field's internal name (`md1stin`) until the game sets its own location
  text.
- Checked in fields and battles; world map, menus and the minigames are untested.
- Mid-effect battle states: FFNx keeps per-effect interpolation state on the host side; a load
  in the middle of a spell may show its first frames oddly.
