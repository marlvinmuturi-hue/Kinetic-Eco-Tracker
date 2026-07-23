# Geoid grid for mean-sea-level altitude

The app converts GPS **ellipsoidal** altitude (what Android returns) to **mean-sea-level (MSL)**
altitude using the EGM96 geoid model. To activate this, drop the geoid grid file here:

    app/src/main/assets/egm96-15.dac

**Until this file is present, altitude stays ellipsoidal and everything works exactly as before**
(the correction is inert — see `GeoidService.kt`).

## Which file

The canonical NGA **`WW15MGH.DAC`** (EGM96, 15-arc-minute grid). Rename it to `egm96-15.dac`.

- Size: exactly **2,076,480 bytes** (721 rows × 1440 cols × 2-byte big-endian centimetres).
- Public domain. Mirrors:
  - NGA / earth-info Office of Geomatics (EGM96 downloads)
  - NOAA NGS
  - GeographicLib geoid data (note: GeographicLib ships a `.pgm`; this app expects the raw NGA
    `.DAC`. Use the NGA `WW15MGH.DAC`, not the `.pgm`.)

After adding the file, rebuild. On the next tracked session Logcat shows:

    GeoidService: Geoid grid loaded (721×1440, 2076480 bytes) — MSL altitude correction active

and each fix applies `MSL = ellipsoidal − N`. If the file is absent you'll instead see:

    GeoidService: No geoid grid at assets/egm96-15.dac — altitude stays ellipsoidal (correction inactive)

## Verifying it's right for your device

Some GNSS chips already report MSL despite the Android contract. After adding the grid, sanity-check
a known location's altitude against a map. If altitude now reads worse (off by ~the geoid separation
N for your area), your device was already correcting — remove `egm96-15.dac` to disable.