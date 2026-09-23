# androidTest assets — provenance (test-only, never shipped in any APK)

These images exercise the on-device detectors under instrumentation
(`PersonDetectionTest`, `FaceDetectionTest`, `Phase3EnginesSmokeTest`).
All three are U.S. NASA portraits: works of the U.S. federal government
with no known copyright restrictions (public domain). Each was photographed
by a NASA-JSC staff photographer; no contractor credit applies.

| File | Subject | NASA ID | Source page | Direct file | sha256 |
|---|---|---|---|---|---|
| `astronaut.png` (791555 B, 512x512) | Eileen Collins portrait (flag + shuttle backdrop) | NASA Great Images (`flic.kr/p/r9qvLn`) | https://www.flickr.com/photos/nasacommons/ (Great Images set) | byte provenance before the 2026-08 repo flatten is unrecorded; content matches the NASA Eileen Collins portrait also distributed as `skimage.data.astronaut`, whose docs record "downloaded from the NASA Great Images database ... released into the public domain"; PD status derives from NASA authorship | 88431cd9653ccd539741b555fb0a46b61558b301d4110412b5bc28b5e3ea6cb5 |
| `meir_portrait.jpg` (151632 B, 1023x1279) | Jessica Meir, official EMU portrait, JSC Building 8, 2025-09-26 | `jsc2025e078605_alt` | https://images.nasa.gov/details/jsc2025e078605_alt | http://images-assets.nasa.gov/image/jsc2025e078605_alt/jsc2025e078605_alt~medium.jpg | 3177035d2ef10190bf921fc223cd2624293c75a6e74d9e32c071d31df1c08473 |
| `hathaway_portrait.jpg` (154920 B, 1023x1280) | Jack Hathaway, official EMU portrait, JSC Building 8, 2025-09-19 | `jsc2025e068207_alt` | https://images.nasa.gov/details/jsc2025e068207_alt | http://images-assets.nasa.gov/image/jsc2025e068207_alt/jsc2025e068207_alt~medium.jpg | 91eb3a34ac12c6fe7a84d12d36f0925d0466ae05020d4f68103f65eb9ea6b607 |

Attribution (keep with any redistribution):
"Portraits by NASA / Josh Valcarcel (Johnson Space Center); Eileen Collins
portrait via the NASA Great Images collection. U.S. public domain."

History: `messi5.jpg` (watermarked agency sports photo, OpenCV samples) and
`camera.png` (pre-0.18 scikit-image "cameraman", replaced upstream over
copyright) were removed 2026-09-24 for unclear provenance and replaced with
the NASA portraits above; test lists renamed accordingly (no assertion
changes — decoded boxes remain pixel-clamped per YoloPostprocess).
