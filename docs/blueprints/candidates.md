# Form factors

The same electronics (XIAO ESP32-S3 Sense, a LiPo cell, the kit antenna, one button) can be worn,
held, placed or hidden in many ways. This file is the catalogue: the three builds that have full
blueprints, the wider set of shapes, the people they were designed around, and the ways to make
them beyond a printed box.

One fact shapes every wearable here: **the camera module stacked on the Sense board makes the
assembly 15 mm deep**. Unplugged and re-seated on its own 24-pin ribbon, the module sits beside the
board and the whole assembly drops to 8.5 mm, with the battery alongside instead of behind. Every
envelope below assumes that. A longer FPC extension (24-pin, 0.5 mm pitch, same-side contacts,
50–150 mm, ≈ $3) is the single accessory that unlocks the split designs (camera in one place, board
and battery in another); it is marked **ext** where needed and must be verified for contact
orientation before ordering.

## Built: full blueprints in this folder

| Build | Envelope | Cell | Runtime stream / stills | Folder |
|-------|----------|------|-------------------------|--------|
| **Lapel badge / pendant** | 56 × 31 × 14.5 mm, ≈ 19 g | LP502030 | 1.5 h / 4.5 h | [`lapel-badge/`](lapel-badge/) |
| **Cap-brim clip** | 46 × 36 × 14.5 mm (+3 mm plate), ≈ 23 g | LP502030 | 1.5 h / 4.5 h | [`cap-brim-clip/`](cap-brim-clip/) |
| **Point-and-ask fob** | 74 × 29 × 13.9 mm, ≈ 28 g | LP502540 | 3 h / 9 h | [`point-and-ask-fob/`](point-and-ask-fob/) |

The first version of this file quoted smaller envelopes (28 × 36 × 13 for the pin, 44 × 24 × 10 for
the cap clip); those assumed the camera could stay stacked. They could not be built; the numbers
above come from the models.

## The wider set

Grouped by where the camera ends up. "Shell" says which printed shell it reuses.

### On the chest

| # | Form | Envelope | How it is worn | Shell / parts | Notes |
|---|------|----------|----------------|---------------|-------|
| 1 | Lapel badge | 56 × 31 × 14.5 | magnets through the shirt | built | the default |
| 2 | Pendant | same, with bail | 3 mm lanyard, chest height | `lapel-badge.scad` with `bail = true` | swings when walking: stills mode |
| 3 | ID-card badge | 86 × 54 × 11 | lanyard or badge reel, like a conference badge | new shell, same layout plus a printed name-plate face | the most socially legible: a camera on a *badge* reads as "staff", not "spy"; room for a 0.96" OLED showing *WhoIsThis · recording* |
| 4 | Strap clip | 56 × 31 × 14.5 + clip | backpack / bag strap, seat-belt style | lapel shell + a 25 mm webbing clip back instead of magnets | for commuters and students |
| 5 | Brooch / pin-back | 56 × 31 × 14.5 | bar pin through fabric | lapel shell + sewn-on pin bar | for jackets magnets cannot grip |
| 6 | Tie clip / collar clip | 60 × 14 × 12 **ext** | the camera alone in a 14 mm clip on the tie or collar, board and cell in the shirt pocket | split build | smallest visible part (≈ 12 × 12 mm lens block) |

### On the head

| # | Form | Envelope | How it is worn | Shell / parts | Notes |
|---|------|----------|----------------|---------------|-------|
| 7 | Cap-brim clip | 46 × 36 × 14.5 | magnets through the brim | built | best viewpoint |
| 8 | Headband / headlamp | 46 × 36 × 14.5 | elastic 25 mm band, forehead | cap-clip shell + band loops instead of magnets | sports, nurses on night shift, anyone without a cap |
| 9 | Glasses temple pod | 24 × 20 × 12 pod + 7 mm channel, 58 mm long | clip on one temple, camera forward at the hinge | new shell; LP401230 110 mAh | 0.7 h / 2 h; 12 g on one ear; the closest to the glasses plugin |
| 10 | Glasses bridge + pocket **ext** | lens block 12 × 12 × 8 on the bridge or hinge, board + cell in a 56 × 31 × 12 pocket unit | 150 mm FPC down the temple into the pocket | split build | the lightest thing on the face (≈ 3 g); exposed ribbon needs a sleeve |
| 11 | Over-ear hook **ext** | 50 × 20 × 12 behind the ear, camera on a 40 mm stalk forward | like a bone-conduction headset | split build, TPU hook | leaves the glasses alone; works with hats |
| 12 | Beanie / hat crown button | 40 × 40 × 13 | through the fabric of a beanie, magnets | round shell variant | camera a little high; winter version of the cap clip |

### In the hand or on the body

| # | Form | Envelope | How it is used | Shell / parts | Notes |
|---|------|----------|----------------|---------------|-------|
| 13 | Point-and-ask fob | 74 × 29 × 13.9 | pointed and pressed | built | the consent-first one |
| 14 | Pen / stylus | Ø 16 × 150 **ext** | clipped in a shirt pocket, camera in the cap end looking out | tube shell, LP401230 or 10440 cell | board does not fit a Ø16 tube: it is 17.8 wide; Ø 22 tube or a flattened "marker" 24 × 14 section |
| 15 | Wristband | 46 × 36 × 14.5 on a 22 mm strap | camera on the edge, raised arm | cap-clip shell + strap bars | awkward for faces; good for "show me this badge/label" |
| 16 | Belt / holster | 74 × 29 × 13.9 | fob in a TPU holster, camera forward at hip height | fob + holster print | hip height sees faces only at 2 m+; for guides and security staff |
| 17 | Phone back module | 56 × 31 × 14.5 | magnet ring on the phone, camera facing forward while the screen faces you | lapel shell + MagSafe-style ring | a second, forward camera while reading the app: the phone's own rear camera already does this, so only worth it with the display puck idea below |

### Placed

| # | Form | Envelope | Use | Shell / parts | Notes |
|---|------|----------|-----|---------------|-------|
| 18 | Desk / door puck with OLED | 62 × 44 × 20 | USB-C powered, shows the name | new shell, 0.96" OLED, 30 × 10 antenna | reception, front door, classroom |
| 19 | Monitor / laptop-top clip | 56 × 31 × 14.5 + clip | on the laptop lid during video calls | lapel shell + lid clip | "who just joined the room" |
| 20 | Mirror / wardrobe mount | 46 × 36 × 14.5 | sticks on the hall mirror, faces the door | cap-clip shell + 3M VHB | a doorbell that knows the family |
| 21 | Tripod / GoPro mount | fob or badge shell + ¼" nut or GoPro fingers | event tables, stage side | small adapter print | the GoPro ecosystem gives every mount for free |

## People, and what each would ask for

(How each of them would *use* the device day to day is in [`personas.md`](personas.md).)

Each persona starts from how they meet people, what they must not do, and what they would be proud
to wear. The form they point at is in the tables above.

| Persona | How they meet people | What matters | Form they would pick | Why |
|---------|---------------------|--------------|----------------------|-----|
| **Artist / gallerist** | openings, studio visits, collectors they met once | it must look intentional, an object, not a gadget | pendant (2) in cast resin, or brooch (5) in brass-filled filament | the device is part of the outfit; the lens window becomes the "gem" |
| **Student** | lecture halls, clubs, 200 new names a semester | cheap, survives a backpack, no soldering iron at the dorm | strap clip (4) or ID-card badge (3) | lives on the bag strap or the student card lanyard; USB-C charging with the laptop |
| **Engineer (the one who built it)** | meetups, conferences, hallway tracks | wants to see it work, swap parts, add an OLED | ID-card badge (3) with the OLED, or the desk puck (18) on the bench | a conference badge is the honest place for a camera; a debug display beats a blinking LED |
| **Architect / designer** | site visits, client walk-throughs, hard hats | proportion, materials, a clean face | cap-brim clip (7) under a hard-hat brim; pendant (2) in concrete-look resin | the brim keeps it out of the way and shades the lens; a "pebble" pendant matches a designer's taste |
| **Politician / campaigner** | rope lines, rooms full of people who expect to be remembered | discreet but never hidden; the staff rule is "wearable must be declared" | lapel badge (1) shaped as a flag/party pin; tie clip (6) with the board in the pocket | a camera in a lapel pin is a known form (campaign buttons); the tie-clip split keeps the visible part tiny; the app's hedged labels protect against a wrong name |
| **Doctor** | wards, rounds, 40 patients and 60 colleagues a day | hygiene: wipe-clean, no fabric, no dangling lanyard; shift-long battery | potted/resin-coated ID-card badge (3) on a retractable reel, 500 mAh | smooth epoxy surface, no seams to harbour anything; the badge reel is already hospital-standard |
| **Nurse** | night shifts, masks, moving fast | hands free, cannot be caught on anything, visible charge state | headband (8) under the cap, or the badge (3) with the OLED showing battery | a brim clip is useless under a surgical cap; a flat headband works; the OLED answers "will it last the shift" |
| **Teacher** | 150 students, parents' evenings | signals consent visibly to minors and parents; easy to switch off and show it is off | desk puck (18) on the teacher's desk, slide-switched; fob (13) for the corridor | a stationary unit facing the door is explainable; the fob only captures on a visible press |
| **Hero (first responder, firefighter, paramedic)** | strangers in bad situations, helmets, gloves | glove-sized button, helmet mount, rugged, IP-rated | fob (13) in a TPU holster (16); cap-clip shell on a helmet GoPro mount (21) | big tail button works with gloves; GoPro mounts already exist on helmets |
| **The Tony Stark build** | everyone, with theatre | the object itself is the statement: glowing lens ring, voice, HUD | glasses bridge split (10) with an OLED "HUD" in the pocket unit and a buzzer/voice, or a brass pendant (2) with an LED ring around the lens | the firmware already has `display.text`; add a 12-LED ring (WS2812) driven from a free pin and a tiny speaker for the "label spoken by the device" capability |
| **Man, tailored** | suits, shirts with collars | pocket square / tie / lapel real estate | tie clip (6), lapel badge (1) as a stud | the collar and lapel are the designed places for small metal objects |
| **Woman, tailored** | dresses without lapels, necklines, hair | no pin holes in silk, no weight pulling fabric, jewellery scale | pendant (2) on a chain, brooch (5) with a magnet back for knitwear, hair-clip camera **ext** (a 12 × 12 lens block on a barrette, board in a pocket) | magnets and chains instead of pins; the split build keeps the visible part jewellery-sized |
| **Parent** | school gates, birthday parties | kids must not be the target; obvious on/off | fob (13) | only captures on a press, lives on the key ring |
| **Guide / host / MC** | 30 guests whose names they were told once | hands busy, speaking, moving | headband (8) or badge (3) with the OLED facing the host (name prompt) | the OLED makes it a teleprompter for names |
| **Security / door staff** | every face at the entrance | fixed viewpoint, all-night power | desk/door puck (18) at the door, phone on the desk | mains power, antenna relocated, no battery |
| **Visually impaired user** | everyone, by voice | the device must *tell* them, not show them | pendant (2) + bone-conduction headset paired to the phone; or a buzzer pattern on the device | the app already speaks the label; the device adds a tap-confirmed "listening" chirp |

Threads that run through the table: the **split build (ext)** serves anyone who wants the visible
part small; the **ID-card badge** serves anyone who already wears a lanyard; the **fob** serves anyone
who needs consent to be obvious; the **puck** serves anyone with a desk or a door.

## Ways to make it

| Method | Where it fits | What it costs | Notes |
|--------|---------------|---------------|-------|
| **FDM print, PETG/PLA** | every shell here | ≈ 1 h print, $0.50 | 1.6 mm walls, 0.3 mm clearances; the three STL sets are tuned for it |
| **Resin (SLA/MSLA) print** | pendants, glasses pods, the pen | $2 of resin | 1.0 mm walls hold, so every envelope shrinks by ≈ 1.2 mm per axis; smooth enough to look like jewellery; use tough resin for the lid lip |
| **Epoxy potting** | doctor's badge, pendant "pebble", anything wipe-clean | $5 | pot only the board + camera + antenna in a silicone mould with clear epoxy over the lens; **never pot the cell** (swelling, replacement) and leave the USB-C open with a tape plug during the pour. Heat: the Sense board hits 60 °C while streaming, epoxy slows the cooling: run stills, not video, in a potted build |
| **Cast resin over a printed core** | artist / designer pendants | $8 | print the core (board holder), cast a clear or pigmented shell around it in a silicone mould; the lens window is a polished dome of the same resin |
| **Two-part print + TPU gasket / overmold** | hero builds, cap clip in rain | $1 | TPU lid gasket, TPU bumper around the shell; IP54-ish with a conformal coat on the board |
| **Laser-cut acrylic sandwich** | desk puck, ID badge | $3 | 3 mm plates on M2 standoffs, clear top; shows the electronics off (the engineer's choice) |
| **Off-the-shelf box** | puck, pocket unit | $3 | Hammond 1551 series (e.g. 1551K, 80 × 40 × 20) drilled for lens, USB-C and switch; nothing to print |
| **Heat-shrink "stick"** | fastest prototype | $0.20 | board + cell wrapped in 30 mm heat-shrink with the camera poking out; proves the plugin in an afternoon |
| **Metal-filled filament, polished** | brooch, pendant | $2 | brass- or bronze-fill PLA, sanded and polished: looks like cast metal; keep the antenna area unfilled (metal fill attenuates Wi-Fi) |
| **Sewn fabric pouch** | strap clip, headband | $1 | neoprene pouch with a grommet for the lens, Velcro back; soft, washable (cell out first) |
| **Wood or leather skin** | architect / artist versions | $5 | 0.6 mm veneer or 1 mm leather glued over the printed shell; the lens ring and button cap stay printed |
| **CNC aluminium** | the Tony Stark one | $40 at a hobby shop | anodised shell; the antenna must then go outside (30 × 10 flex under a plastic end cap) |

## Follow-ups the catalogue implies

- ~~Firmware `WIT_CAPTURE_ON_TAP` mode for the fob~~ — done (firmware 1.1: `/capture?wait=`, stream
  server compiled out, modem sleep and camera off between presses); bench-measure standby current.
- Firmware `WIT_LED_RING` (WS2812, 12 px) for the lens-ring builds; `WIT_SPEAKER` for a device-side
  chirp, which would let the plugin report `audio.bytes`.
- A 24-pin FPC extension verified on the bench (pitch, contact side, length) to unlock the split builds.
- An ID-card badge shell (86 × 54 × 11) with the 0.96" OLED: it serves four personas at once.
