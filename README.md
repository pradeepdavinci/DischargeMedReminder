# Discharge Med Reminder (Android) – fully open source

Free and open-source (MIT License) · works offline · no Google services · not a medical device (see DISCLAIMER.md)

Photograph the medication sheet a patient receives at hospital discharge. The app reads it on the
phone, lets you check and correct every medicine, then sends a notification at each dose time for
the length of each course.

## Features
**Capturing the sheet**
* Photograph up to 5 pages with the phone's camera, or pick photos from the gallery.
* Automatic page detection; drag the 4 corner dots (with a magnifier) to fine-tune, rotate if needed.
* Perspective correction ("flattens" a sheet photographed at an angle) and shadow / uneven-light removal.

**Reading the text (100 % open source, offline)**
* Tesseract OCR 5 with the most accurate English model (tessdata_best).
* Each page is read several ways (table layout, free layout, original photo); the reading that
  yields the most recognisable medicines wins.
* Repairs typical OCR mistakes (`I-O-I` → `1-0-1`, `rng` → `mg`).

**Understanding prescriptions**
* Dose patterns `1-0-1`, `1-1-1`, `0-0-1`, `½-0-½`, `1-0-0-1`
* `OD / BD / TDS / QID`, `once / twice / thrice daily`, `every 6 hours`, `q8h`
* `morning / afternoon / evening / night / bedtime / HS`
* Weekly, alternate-day and "every N days" medicines
* `SOS / PRN / as needed` (listed, no automatic reminders)
* Durations `x 5 days`, `for 2 weeks`, `1 month`, `continue` (30 days, flagged)
* `before food / after food / empty stomach`; Tab/Cap/Syp/Inj/T./C. forms, numbered table rows,
  name and instructions split over two lines

**Checking (mandatory)** – every medicine appears in an editable form next to the text that was
read; uncertain items are flagged ("Duration not found – please check"). Start date, times,
interval, duration and notes are editable; medicines can be edited later.

**Reminders**
* High-priority notification with **Taken** and **Snooze 10 min**.
* Automatic repeat reminder if a dose is not marked as taken (default 15 min, configurable).
* "Today" checklist on the home screen (taken ✓ / overdue ⚠ / upcoming).
* Survives restarts, time-zone and clock changes; stops when each course ends.
* **Settings**: the patient's usual morning / afternoon / evening / night times.
* **Share schedule** as text with family or carers.

## Get the APK – choose one
The build downloads the English OCR model (~15 MB) automatically the first time.

### A. GitHub (no software to install)
1. Create a new **public** GitHub repository and upload all files in this folder (keep the folder structure, including `.github`).
2. Open the **Actions** tab → *Build APK* runs automatically (or press *Run workflow*).
3. After about 5–10 minutes, open the finished run and download **DischargeMedReminder-apk**; unzip it to get `app-debug.apk`.
4. To publish a download for everyone, create a GitHub *Release* and attach the APK.

### B. Azure DevOps
Push this folder to an Azure Repos repository and create a pipeline from `azure-pipelines.yml`.
The APK is published as the pipeline artifact **DischargeMedReminder-apk**.

### C. Android Studio
Open this folder in Android Studio (Ladybug or newer), let Gradle sync, then
**Build › Build App Bundle(s) / APK(s) › Build APK(s)**. Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Install on the phone
1. Copy `app-debug.apk` to the phone and open it; allow **Install unknown apps** when asked.
2. On first launch allow **Notifications**, then tap **Test** to confirm reminders work.
3. Recommended: Settings › Apps › Discharge Med Reminder › Battery › **Unrestricted**
   (some phones – Xiaomi, Oppo, Vivo, Samsung – otherwise delay alarms).
4. Open **Settings** in the app and set the patient's usual meal / bed times.

Requirements: Android 7.0 or newer. APK size about 40–50 MB (OCR engine for all phone types plus the model).

## Tips for the best scan
* Lay the sheet flat on a darker surface, in good even light, and fill the frame with the medicine list.
* Hold the phone parallel to the paper; avoid flash glare.
* Check every medicine on the review screen – OCR is never perfect.
* Handwritten prescriptions are read poorly; add those medicines with *Add a medicine manually*.

## Publishing on F-Droid
The app has no proprietary dependencies and includes F-Droid store text in `fastlane/`.
F-Droid builds from source: point the build recipe's prebuild step at the same model download
(see the `downloadTessModel` task in `app/build.gradle.kts`) or vendor the model file into the repo.

## Tests
`gradle testDebugUnitTest` runs the sheet-reading tests (`app/src/test/...`). They also run in the GitHub
and Azure pipelines. Please add a test with every new prescription format you support.

## Project layout
```
app/src/main/java/com/discharge/medreminder/
  MainActivity.kt      home screen: capture, today's checklist, medicines, share
  CropActivity.kt      per-page corner adjustment and rotation, starts reading
  CropView.kt          draggable corner handles with magnifier
  ImageTools.kt        loading, page detection, perspective correction, shadow removal
  OcrPipeline.kt       Tesseract OCR, multi-pass reading and selection
  MedParser.kt         turns sheet text into medicines, times, intervals and durations
  ReviewActivity.kt    check / edit medicines before saving
  SettingsActivity.kt  usual dose times, repeat-reminder interval
  Scheduler.kt         exact alarms per dose, taken tracking
  Notifier.kt          notifications with Taken / Snooze
  Receivers.kt         alarm, restart and clock-change handling
  Model.kt, Prefs.kt   on-device storage
app/src/test/.../MedParserTest.kt   sheet-reading tests
```

## Licence
MIT – see LICENSE. Third-party licences: THIRD_PARTY_NOTICES.md. Privacy: PRIVACY.md.
