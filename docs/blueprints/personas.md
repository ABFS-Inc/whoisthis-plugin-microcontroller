# Personas: how different people would want to use the device

`candidates.md` says which shape each persona would pick. This file is about **use**: when the
camera is on, how the person triggers it, how they want the answer delivered, what ritual makes it
acceptable to the people in front of them, and how it fits their day. Each row ends in what the
firmware, the plugin or the app would need. The firmware and plugin today support: continuous
stream or stills every N seconds, `tap` / `long_press` events, battery, `present()` to an OLED and
LED (buzzer optional). Everything beyond that is listed as a follow-up at the end.

## The three use styles

Almost every persona falls into one of three styles, and the device should offer all three as
firmware modes selectable from the plugin screen:

| Style | Camera | Trigger | Answer | Who |
|-------|--------|---------|--------|-----|
| **Companion** | streaming or stills whenever worn | none; the app looks up on its own | spoken in an earbud or shown on the phone | people who meet a crowd and cannot stop to press anything |
| **Ask** | asleep until a press | tap = one still; long press = off | spoken, buzzed, or on the device OLED | people for whom the press *is* the consent |
| **Post** | fixed viewpoint, always on, mains powered | none | shown on the device OLED, to the host | desks, doors, rooms |

## Persona by persona

### Artist / gallerist — *"it has to be an object I would wear anyway"*
- **Style:** Companion at openings, Ask in the studio.
- **Use:** wears the pendant like jewellery; wants the lens window to read as a stone or a lens-shaped
  gem, the lid as a polished back. Charges it on a tray with the rings, so a magnetic charging dock
  or at least a USB-C that is not ugly (recessed, with a cap).
- **Answer:** quiet. One earbud; never a buzzer in a gallery. On the phone a *recent faces* strip they
  can scroll after the evening to write thank-you notes.
- **Consent ritual:** the object is visible and a conversation piece; they would rather explain it
  than hide it. A small engraved "camera" glyph on the face.
- **Needs:** pendant shell cast in resin or metal-fill; firmware `WIT_LED_PIN -1` (no blinking on the
  chest); app side: a post-event recap of who was recognised.

### Student — *"cheap, chargeable from my laptop, survives a backpack"*
- **Style:** Companion in the first weeks of term (lecture halls, clubs), then Ask.
- **Use:** on the bag strap or the student-card lanyard. Charges from the laptop with the same cable as
  the phone. Expects it to just pair over the campus Wi-Fi, which is often enterprise Wi-Fi where
  mDNS and device-to-device traffic are blocked, so the phone-hotspot path matters more than mDNS.
- **Answer:** on the phone, with the context ("same lab group as you, met at the robotics club").
- **Consent ritual:** a visible lanyard badge; a sticker; "it only tells me who I have already met".
- **Needs:** firmware fallback that joins the **phone's hotspot** (SSID/password typed once in the
  plugin screen, pushed to the board over the portal); strap-clip shell; cheapest BOM (no magnets).

### Engineer — *"show me it working, let me change it"*
- **Style:** all three, by the hour.
- **Use:** the ID-card badge with the OLED at conferences, the desk puck on the bench. Wants a status
  page in the browser (the firmware's `/` page), serial logs, frame counters, and a way to swap the
  camera module for the OV5640. Will add things: a WS2812 ring, a second button, an IMU.
- **Answer:** on the OLED (name + confidence), plus the phone.
- **Consent ritual:** the badge *says* it is a camera; the OLED shows *recording* while streaming.
- **Needs:** `display.text` already works; add a *streaming* indicator on the OLED driven by the
  `onState` transitions (plugin `present()` with a status string, or a firmware status line); keep the
  pin headers free for additions (the badge shell leaves the +Y edge open).

### Architect / designer — *"proportion, material, nothing that looks like a gadget"*
- **Style:** Companion on site visits (hard hat), Ask with clients.
- **Use:** cap-brim clip under the hard-hat brim on site; in the office a concrete-look resin pendant.
  Wants the shell in one material with one visible seam at most, the button flush, the lens window a
  precise circle. Would pay for a CNC'd version.
- **Answer:** one earbud on site (noise), phone in the office.
- **Consent ritual:** on site everyone wears equipment, a camera on a hard hat is normal (it is how
  site photos are taken). In the office the pendant is visible.
- **Needs:** cap-clip shell with a GoPro-finger adapter for the hard hat's existing mount; a
  resin/cast version of the pendant (see `candidates.md`, fabrication).

### Politician / campaigner — *"I must remember everyone, and I must never be seen to cheat"*
- **Style:** Companion on rope lines, with a staffer holding the phone.
- **Use:** the tiny tie-clip camera with the board in the pocket, or a lapel pin shaped like the
  campaign button. The staffer's phone runs the app; the label is read into the candidate's earpiece
  by the staffer or by the app (the hedged phrasing, "probably Ana, met in Austin", is exactly right).
  Charged by staff overnight, batteries swapped between events (hence the JST pigtail).
- **Answer:** earpiece only, never the device.
- **Consent ritual:** the campaign publishes that staff wear recording devices; the pin is visible.
  Legal review will ask for a hard off: the slide switch, and a visible LED when streaming.
- **Needs:** split build with the FPC extension; plugin support for **two phones** is not needed (the
  staffer's phone is the only one); firmware `WIT_LED_PIN` on and a **streaming LED** behaviour
  (steady while a client is connected to `/stream`).

### Doctor — *"wipe-clean, shift-long, and it must not touch the patient"*
- **Style:** Companion on rounds, Post in the consulting room.
- **Use:** potted ID-card badge on the hospital reel, 500 mAh, charged on the desk between shifts.
  Faces recognised are colleagues and long-term patients; the app must be explicit that it is not a
  medical record. Wants gloves-on operation: no small buttons at all; the reel pull is the gesture.
- **Answer:** on the phone, glanced at between rooms; never spoken aloud near patients.
- **Consent ritual:** staff badge, declared to the ward; patients told at admission if it is used at
  all. In many hospitals the realistic use is **staff only**, and the device is off in patient rooms:
  a geofence or a "ward mode" toggle in the app.
- **Needs:** potted shell (no seams), `WIT_BUTTON_PIN -1`, long cell; app side: a one-tap "pause for
  this room" with a visible paused state on the OLED.

### Nurse — *"hands free, nothing dangling, will it last the night?"*
- **Style:** Companion on night shift (masks hide faces: the device helps with colleagues' names).
- **Use:** flat headband under the cap, or the badge on a reel. Charges at the station on a dock they
  can drop it onto one-handed. Masks reduce recognition; the device should still work on the eyes
  and brow, which is the app's job.
- **Answer:** phone, and a **battery figure** they can trust on the device (OLED or three-LED gauge),
  because a dead device at 3 a.m. is worse than none.
- **Consent ritual:** same as the doctor; worn as part of the uniform.
- **Needs:** headband variant of the cap-clip shell; battery divider fitted; firmware battery event
  thresholds at 20 % and 10 % (buzz once); a charging dock print (two pogo pins to BAT pads would be
  the deluxe version; simplest is a USB-C cradle).

### Teacher — *"the students and their parents must see it is fair"*
- **Style:** Post in the classroom, Ask in the corridor.
- **Use:** desk puck facing the door, switched on with a visible switch during parents' evenings, off
  during lessons. The fob on the key ring for the corridor and the playground: it only captures when
  pressed, which they can demonstrate.
- **Answer:** OLED on the puck turned toward the teacher; phone for the fob.
- **Consent ritual:** a sign on the desk; the puck's OLED shows *recording* / *off*; a physical switch
  the students can see. Minors: the school's policy decides; the design makes "off" provable.
- **Needs:** desk puck shell; OLED status line; fob `WIT_CAPTURE_ON_TAP` mode.

### Hero — first responder, firefighter, paramedic — *"gloves, helmet, rain, now"*
- **Style:** Ask (glove press) or Companion on a helmet mount during an incident.
- **Use:** fob in a holster on the belt, or the cap-clip shell on the helmet's GoPro mount. Big tail
  button, TPU gasket, conformal-coated board. Charged in the vehicle (12 V USB-C). Used to confirm
  a colleague's name on a multi-agency scene, or a known patient, not strangers.
- **Answer:** spoken in the radio earpiece (the app can route TTS to a Bluetooth headset).
- **Consent ritual:** body-worn cameras are already policy for many services; this one is declared
  as part of that policy. Visible streaming LED.
- **Needs:** holster and helmet-mount prints; buzzer on; a **large-plunger button** (12 mm cap);
  IP54 build notes (gasket, coating).

### The Tony Stark build — *"the device should talk, glow and be a little too much"*
- **Style:** Companion, with theatre.
- **Use:** glasses-bridge camera with the ribbon down the temple to a pocket unit, or a pendant with a
  12-LED ring around the lens that breathes while streaming and flashes when a label arrives. A
  speaker in the pocket unit says the name ("J.A.R.V.I.S." voice optional, but it is the phone's TTS
  rendered to WAV and played by the device). Charges on a glowing dock.
- **Answer:** on the device: ring animation + voice; OLED HUD on the pocket unit.
- **Consent ritual:** it is impossible to miss; that is the point.
- **Needs:** firmware `WIT_LED_RING` (WS2812 on a free pin: idle breathe, streaming solid, present
  flash); a small I²S amp + 20 mm speaker on the pocket unit so the plugin can report
  `audio.bytes` and play the phone-rendered label (the glasses plugin already renders WAV through the
  phone's TTS; the plugin here would `POST /speak` with the WAV); the split build with the FPC
  extension.

### Man, tailored — *"the lapel and the tie are where small metal things go"*
- **Style:** Companion at events.
- **Use:** tie-clip camera (split build) or a lapel stud; the board and cell in the inside jacket
  pocket, the ribbon along the lining. Charges with the watch at night.
- **Answer:** earbud or a glance at the phone.
- **Consent ritual:** the clip is visible and looks like a tie clip with a lens; he tells people.
- **Needs:** split build; a brass-fill or polished-resin clip; the pocket unit is the badge shell
  without the lens window.

### Woman, tailored — *"no pin holes in silk, nothing heavy on a neckline, jewellery scale"*
- **Style:** Companion at events, Ask otherwise.
- **Use:** pendant on a chain (the chain carries the weight, no pin), a magnet-back brooch on knitwear,
  or a hair-clip camera (12 × 12 lens block on a barrette, ribbon under the hair to a pocket unit). A
  clutch has no pocket: the pocket unit clips inside the bag, so the ribbon length matters
  (150 mm) or the pendant has to be self-contained (it is: the badge shell).
- **Answer:** earbud; the phone stays in the bag.
- **Consent ritual:** visible jewellery; a sincere "it helps me remember names" works better than any
  sticker.
- **Needs:** pendant shell with a proper bail and a chain-safe lid (magnets attract the chain:
  use the snap lid without magnets); hair-clip split build; weight under 20 g.

### Parent — *"never aimed at children by accident"*
- **Style:** Ask only.
- **Use:** the fob on the key ring; pressed at the school gate to recall another parent's name. Lives
  in the bag; charged rarely (500 mAh).
- **Answer:** phone.
- **Consent ritual:** the press, done openly; no streaming mode at all in this profile.
- **Needs:** fob with `WIT_CAPTURE_ON_TAP` and the stream endpoint disabled in that config (compile
  out port 81); the app profile that never auto-looks-up.

### Guide / host / MC — *"thirty names I was told once, and I am talking the whole time"*
- **Style:** Companion.
- **Use:** headband or badge with the OLED facing them, acting as a name prompter; or the cap clip on a
  tour. Charged between tours on a dock.
- **Answer:** OLED first (silent, no earbud while speaking), earbud second.
- **Consent ritual:** announced at the start of the tour; the badge says *host*.
- **Needs:** OLED on the badge; `present()` text size large (the firmware's 2× font for short names).

### Security / door staff — *"every face at the entrance, all night"*
- **Style:** Post.
- **Use:** door puck at 1.4 m, mains powered, relocated antenna; the phone on the desk runs the app.
  Names come from the guest list; unknowns are just unknown.
- **Answer:** OLED on the puck facing staff, phone.
- **Consent ritual:** a sign at the door; it is the normal place for a camera.
- **Needs:** desk/door puck shell; `WIT_BATTERY_PIN -1`; stream at QVGA–VGA for all-night thermal
  margin (the firmware's `max` parameter already allows it).

### Visually impaired user — *"tell me, do not show me"*
- **Style:** Companion.
- **Use:** pendant worn all day, a bone-conduction headset paired with the phone. The device's own
  feedback must be non-visual: a buzzer chirp on tap ("I heard you"), a double chirp when a label is
  on its way, a battery pattern on long press.
- **Answer:** speech from the phone through the headset; the app already speaks the hedged label.
- **Consent ritual:** the pendant is visible to others; the user explains it as an assistive device,
  which it is.
- **Needs:** buzzer patterns in the firmware for tap-ack, present, low battery (three distinct
  chirps); a tactile lens-ring so the wearer can feel which way it faces; a long-press "say battery"
  that the plugin turns into a spoken percentage through the phone.

## What this changes in the plan

Features that several personas need, ordered by how many ask for them:

1. **Capture-on-tap mode** (parent, teacher, hero, fob users): `WIT_CAPTURE_ON_TAP`; `/capture`
   returns a frame only after a press; Wi-Fi modem sleep between; stream server compiled out.
   *Done in firmware 1.1 and the plugin (`/capture?wait=`, `capture=on_tap`); bench measurements open.*
2. **Streaming / off indicator on the device** (politician, engineer, teacher, hero): LED steady while
   a `/stream` client is connected, OLED line *recording* / *off*; firmware only.
3. **Battery you can trust** (nurse, doctor, visually impaired): divider fitted in every wearable
   build; buzzer pattern at 20 % and 10 %; OLED gauge where there is an OLED.
4. **Phone-hotspot fallback** (student, hero, anyone off their home network): the plugin screen pushes
   the phone's hotspot SSID/password to the board through its portal; the plugin then connects to
   the board's IP on the hotspot subnet.
5. **Buzzer vocabulary** (visually impaired, hero, parent): tap-ack, label-arrived, low-battery,
   paused; three or four distinct chirps, documented.
6. **Device-side voice** (Tony Stark, visually impaired without a headset): I²S amp + speaker,
   `POST /speak` with phone-rendered WAV, plugin reports `audio.bytes`.
7. **Pause for this room** (doctor, teacher): app-side, with the paused state mirrored on the device.
8. **Split build parts** (politician, tailored man and woman, Tony Stark): verify the 24-pin FPC
   extension and add the tie-clip / hair-clip / glasses-bridge lens block and the pocket unit shells.
