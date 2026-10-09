# Live VIP project format (`.mwproj`) — version 1

A `.mwproj` file is a ZIP archive that you prepare **outside the app** (with any image editor and a
text editor, or with `tools/make_sample_project.py` as an example). The app never runs AI models,
never segments images on the phone, and never uploads anything. It only renders the files you supply.

The app rejects an archive with a readable message if any rule below is broken. Nothing is added to the
library in that case.

## Archive rules

| Rule | Limit |
|---|---|
| Archive size | ≤ 150 MB |
| Unpacked size (all files together) | ≤ 300 MB |
| Entries | ≤ 200 files |
| Single file | ≤ 40 MB |
| Allowed file types | `.png`, `.json` only |
| Paths | relative, `/` separators, no `..`, no absolute paths, no `:`, ≤ 4 folder levels, ≤ 80 chars per name. A Windows `\` separator and a leading `./` are accepted and normalized |
| Ignored entries | `__MACOSX/` folders, `._*` files, `.DS_Store`, `Thumbs.db`, `desktop.ini` and other dot-prefixed names are skipped (they are archiver metadata, not project content) |
| Duplicates | not allowed |
| Wrapper folder | allowed: if every (non-ignored) entry sits under one folder that contains `manifest.json`, that folder is removed on import |

Only `manifest.json` is mandatory at the top (or inside the single wrapper folder).

## Importing a file

* The app decides whether a file is a project from its **contents**, not its name. The file must start with
  the ZIP signature (`PK`). The file name may be anything, with or without `.mwproj`, `.zip`, or no
  extension. Document providers often report unusual names, so the name is only used in messages.
* A `.mwproj` must be a ZIP archive. Images, PDFs, text files, and empty files are rejected with a message that
  says what the file appears to be.
* Errors name the problem and the exact file or field involved, for example `manifest.json is not valid JSON`,
  `manifest.formatVersion is missing`, `layer 'hair' refers to 'layers/hair.png', which is not in the archive`
  (with a case-sensitivity note when only the capitalization differs), or `Unsupported file 'payload.exe'`.
* A manifest saved with a UTF-8 byte-order mark (common with Windows Notepad) is accepted.
* A rejected import never adds anything to the library.

## Validation report and checklist

The Import screen shows the requirements checklist before any file is chosen. Choosing a file runs the
same checks on the phone and shows a report with these sections:

* **Result**: `VALID`, `VALID WITH WARNINGS`, or `NOT IMPORTED`. Only errors block an import.
* **Missing items (required)**: each missing `manifest.json` field (for example `formatVersion`) and each
  referenced file that is absent, listed by its exact archive path.
* **Unsupported files**: each file with a disallowed extension, listed by its file name.
* **Optional files not included (ignored)**: referenced optional files that are absent. They are warnings,
  not failures. The app ignores them: no depth map means a flat depth, no preview means the background is
  used as the thumbnail, a missing mask or glow mask means that effect is skipped for the layer.
* **Other problems**, **Notes and warnings**, and **Passed checks**.

Two copy buttons put text on the clipboard: **Copy missing items** (the list to fix) and **Copy full report**
(every finding with its code, path, and fix). **Copy checklist** copies the requirements. **Check another
file** re-runs the checks on a corrected file, so the results update.

A check never changes the original file, and nothing is uploaded. The app does not repair projects. Fix the
archive and check it again.

## Archive layout (example)

```
manifest.json
preview.png              optional, library thumbnail (any size ≤ 4096 px)
background.png           required, exactly the canvas size
depth.png                optional, grayscale, exactly the canvas size (white = near, black = far)
layers/body.png          character layers, RGBA, cropped to their bounding box
layers/cape.png
layers/hair.png
layers/sword.png
masks/hair.png           optional per-layer mask, grayscale, same size as its layer
masks/cape.png
masks/sword.png
glow/sword_glow.png      optional glow mask, grayscale, same size as its layer
```

Use `tools/make_sample_project.py` to see a complete working example. The app also bundles its output
(`app/src/main/assets/samples/neon_warrior.mwproj`) and imports it on first launch.

## Images

* PNG only (checked by its signature and IHDR header, not by file name).
* Every side between 1 and **4096 px**.
* Canvas: width and height between **128 and 4096 px**.
* Layers are placed at `x, y` (canvas pixels, may be negative) and must overlap the canvas.
* Total GPU memory for all background, depth, layer, mask and glow images ≈ `width × height × 4` bytes each, and must be ≤ **256 MB**. Crop layers to their bounding box to stay under it. Previews are not counted.

## manifest.json

This short example is the bundled Neon Warrior sample's style. The complete example with a `soft_body` body
layer, hair and cape, masks, depth, particles and effects is in
[`docs/examples/full-example-manifest.json`](examples/full-example-manifest.json). A unit test imports a project
built from it, so the example stays valid.

```json
{
  "format": "mwproj",
  "formatVersion": 1,
  "name": "Neon Warrior",
  "canvas": { "width": 720, "height": 1280 },
  "preview": "preview.png",
  "background": { "file": "background.png", "depth": "depth.png" },
  "layers": [
    { "id": "cape", "role": "cape", "file": "layers/cape.png", "x": 245, "y": 496, "depth": 0.25,
      "mask": "masks/cape.png",
      "rig": { "mode": "ripple", "amplitude": 0.018, "frequency": 0.35, "phase": 0,
               "pivot": [0.5, 0.0], "direction": [1, 0] } },
    { "id": "body", "role": "body", "file": "layers/body.png", "x": 240, "y": 380, "depth": 0.5 }
  ],
  "particles": [
    { "type": "fire", "count": 70, "region": [0, 0.74, 1, 0.26], "color": "#FF7A1A" }
  ],
  "effects": { "outerGlowColor": "#00E5FF", "outerGlowIntensity": 0.8, "innerGlowEnabled": true },
  "motion": { "strength": 1.0, "perspective": 0.5, "smoothing": 6, "motionLimit": 0.6, "idleAmount": 0.2 },
  "mesh": null
}
```

### Top-level fields

| Field | Required | Meaning |
|---|---|---|
| `format` | yes | must be `"mwproj"` |
| `formatVersion` | yes | must be `1` |
| `name` | yes | 1–80 characters |
| `canvas.width`, `canvas.height` | yes | 128–4096 integers |
| `preview` | no | PNG thumbnail path |
| `background.file` | yes | PNG, exactly canvas size |
| `background.depth` | no | PNG depth map, exactly canvas size. Without it the background uses a uniform mid depth (single-image procedural parallax) |
| `layers` | no | up to 24 layers, drawn far (low depth) to near (high depth) |
| `particles` | no | up to 8 emitter groups |
| `effects` | no | default effect values (see below). Missing keys use app defaults |
| `motion` | no | default motion values (see below) |
| `mesh` | no | path to a `.json` mesh file. **Reserved in v1**: the file is checked for existence and size but is not rendered yet |

### Layer fields

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | unique, 1–32 chars of `A–Z a–z 0–9 _ -` |
| `file` | yes | RGBA PNG |
| `role` | no | `body`, `hair`, `cloth`, `cape`, `sword`, `accessory`, `effect`, `other` (default `other`) |
| `x`, `y` | no | canvas pixel position of the top-left corner (default 0) |
| `depth` | no | 0 (far) … 1 (near), default 0.5. Controls how much the layer moves with tilt |
| `mask` | no | grayscale PNG, same size as the layer. White = part that moves with the rig |
| `glowMask` | no | grayscale PNG, same size as the layer. Its brightness drives the glow shape. Without it the layer's alpha is used |
| `rig` | no | motion parameters (below). Without `rig` the layer does not animate |

### Rig fields

| Field | Default | Range | Meaning |
|---|---|---|---|
| `mode` | — (required) | `sway`, `ripple`, `flutter`, `soft_body` | `sway`: slow pendulum. `ripple`: wave travelling through the part (capes, flags). `flutter`: faster, irregular motion (loose hair, fabric edges). `soft_body`: slow breathing plus a light, delayed response to device tilt (chest, torso, soft clothing) |
| `amplitude` | 0.01 | 0 – 0.08 | displacement in layer UV units (fraction of the layer size) |
| `frequency` | 0.4 (`soft_body`: 0.25) | 0 – 3 Hz | oscillation speed |
| `phase` | 0 | any | start offset in radians |
| `pivot` | [0.5, 0] | 0–1 each | UV point the motion is anchored to. Parts farther from the pivot move more |
| `direction` | [1, 0] | any non-zero | movement direction (normalized by the app). For `soft_body` use [0, 1] to breathe vertically |
| `damping` | 0.5 | 0 – 1 | `soft_body` only. 0 follows the device tilt quickly, 1 is heavy and slow. Other modes ignore it |

Without a mask, a rigged layer gets a gentle whole-layer sway (35 % of the amplitude). With a mask,
only the masked pixels move. Independent hair, cloth and sword motion therefore requires separate
layers plus masks. The app never estimates these parts from a flattened image.

#### `soft_body` in detail

A `soft_body` layer responds to two things, both weighted by its mask:

* **Idle breathing.** Runs even when the phone is still. Its amplitude is `amplitude`, its speed is `frequency`.
* **Device tilt.** The layer follows the tilt through a damped lag, so it trails behind the rest of the
  character. The lag is at most about 1.5 % of the layer size at full tilt, and `damping` sets how heavy it feels.

The Effects screen (**Soft body** section) multiplies these values for the device, and the project file is
never changed:

* `softBodyStrength` (0–2): scales the breathing and the tilt response.
* `softBodySpeed` (0.25–3): scales `frequency`.
* `softBodyDamping` (0–2): scales each layer's `damping`, clamped to 0–1.

A `soft_body` layer without a mask moves as a whole with a weak weight, like other rigged layers. Use a
mask to move only the chest or torso.

The full example below shows every part of the format together: three layers (body, hair, cape), their rigs
and masks, depth, particles, and effects.

### Particle fields

| Field | Default | Meaning |
|---|---|---|
| `type` | — (required) | `fire`, `sparks`, `magic`, `ambient` |
| `count` | 40 | 0–300 particles at the default amount |
| `region` | [0, 0, 1, 1] | `[x, y, w, h]`, 0–1, inside the visible screen (y goes down) |
| `color` | type default | `#RRGGBB` or `#AARRGGBB` |

### Effect and motion defaults

Colors are `#RRGGBB` strings. All keys are optional; out-of-range values are clamped.

* effects: `outerGlowEnabled`, `outerGlowColor`, `outerGlowIntensity` (0–2), `outerGlowRadius` (0.002–0.06, fraction of canvas height), `innerGlowEnabled`, `innerGlowColor`, `innerGlowIntensity` (0–2), `glowPulseSpeed` (0–3 Hz), `fireAmount`, `sparksAmount`, `magicAmount`, `ambientAmount` (0–1), `bgBlur` (0–1), `bgBrightness` (−0.5–0.5), `bgContrast` (0.5–1.5), `bgSaturation` (0–2), `bgTintColor`, `bgTintAmount` (0–1), `rigStrength` (0–2), `softBodyStrength` (0–2), `softBodySpeed` (0.25–3), `softBodyDamping` (0–2)
* motion: `strength` (0–2), `perspective` (0–1), `depthScale` (0–2), `smoothing` (1–20), `motionLimit` (0.05–1), `idleAmount` (0–1), `invertX`, `invertY`

Users can change these values in the app. Saved changes are stored in the app's own storage
(`effects.json`) and never written back into the `.mwproj` file.

## Validation summary

1. Archive: size, entry count, names, types, duplicates, compression ratio, and total unpacked size
   (counted while the bytes are actually written, not only from headers).
2. Manifest: JSON syntax and depth, `format`, `formatVersion`, field types, ranges, unique ids, safe paths.
   Unknown fields are warnings and are ignored.
3. Files: every required file exists (`background.png`, each layer `file`); optional files are warnings;
   PNG signature and dimensions; canvas-size background (and depth, when present); masks and glow masks match
   their layer; layers overlap the canvas; GPU memory budget.

The checker collects every problem, not just the first. The import and the library use the same rules.

## Versioning

`formatVersion` changes only for incompatible changes. Version 1 is the only version supported so far.

Adding the `soft_body` rig mode and its optional keys (`damping` on rigs, `softBody*` on effects) did not
change the version. Every existing version 1 project still imports with the same results: none of its rigs
use the new keys, and the defaults for the old modes are unchanged. A project that uses `soft_body` needs an
app version that knows the mode. An older app reports `rig mode 'soft_body'` as an unknown value instead of
guessing.
