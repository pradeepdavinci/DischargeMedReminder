# Discharge Med Reminder (Android) – v2.1 FOSS

Free and open-source (MIT License) · works offline · no Google services · not a medical device (see DISCLAIMER.md)

Photograph or import the medication sheet a patient receives at hospital discharge. The app reads it on the
phone, lets you check and correct every medicine, then sends a notification at each dose time for
the length of each course.

## Features
**Capturing & Rescanning the sheet**
* Photograph up to 5 pages with the phone's camera, pick photos from the gallery, or **import a PDF discharge summary** directly (rendered 100% offline via Android's `PdfRenderer`).
* **One-tap Rescan**: Re-adjust crop corners & rotation on the last scanned sheet without re-photographing, or start a fresh scan right from the review screen or home screen.
* Automatic page detection; drag the 4 corner dots (with a magnifier) to fine-tune, rotate if needed.
* Perspective correction ("flattens" a sheet photographed at an angle) and shadow / uneven-light removal.

**Reading the text (100 % open source, offline)**
* Tesseract OCR 5 with the most accurate English model (tessdata_best).
* Each page is read several ways (table layout, free layout, original photo); the reading that
  yields the most recognisable medicines wins.
* Repairs typical OCR mistakes (`I-O-I` → `1-0-1`, `rng` → `mg`).

**Understanding prescriptions**
* Dose patterns `1-0-1`, `1-1-1`, `0-0-1`, `2-0-2`, `1-0-2`, `½-0-½`, `1-0-0-1` (records explicit per-slot quantities).
* **Tapering schedules**: Splits multi-phase courses (`1-1-1 x 3 days then 1-0-1 x 3 days`) into sequential linked steps.
* `OD / BD / TDS / QID`, `once / twice / thrice daily`, `every 6 hours`, `q8h`
* `morning / afternoon / evening / night / bedtime / HS`
* Weekly, alternate-day and "every N days" medicines
* `SOS / PRN / as needed` (listed, no automatic reminders)
* Durations `x 5 days`, `for 2 weeks`, `1 month`, `continue` (30 days, flagged)
* `before food / after food / empty stomach`; Tab/Cap/Syp/Inj/T./C. forms, numbered table rows,
  name and instructions split over two lines

**Checking (mandatory)**
* Displays a **zoomable preview of the flattened discharge sheet** at the top of the review screen so you can verify medicines side-by-side.
* Interactive **TimePickerDialog clock chips** (`+ Add time`, tap any time chip to edit or remove) alongside manual text editing.
* Optional **Loud continuous alarm** toggle per medicine for critical prescriptions.

**Reminders, Accessibility & Caregiver Tools**
* High-priority notification with **Taken**, **Snooze 10 min**, and **Skip**.
* **Today checklist** on the home screen with high-contrast status badges (`✓ Taken`, `⏭ Skipped`, `⚠ Overdue`, `• Upcoming`), **Undo** button for taken/skipped doses, and **Skip with reason**.
* **Adaptive Dark Mode** and **Large Text Accessibility Mode** in Settings.
* **Share & Caregiver Export menu**:
  * Share schedule as plain text
  * Export `.ics` calendar file for family/caregivers
  * Export a **printable PDF adherence report** for follow-up doctor visits
  * Export & import portable **JSON backups** to sync across devices

## Get the APK – choose one
The build downloads the English OCR model (~15 MB) automatically the first time and produces both lightweight per-architecture APKs (`arm64-v8a` ~20 MB) and a universal APK.

### A. GitHub (no software to install)
1. Push all files in this folder to your GitHub repository (including `.github`).
2. Open the **Actions** tab → *Build APK* runs automatically (or press *Run workflow*).
3. Open the finished run and download **DischargeMedReminder-apk**; unzip it to get the APKs (`app-arm64-v8a-debug.apk` for modern phones or `app-universal-debug.apk` for all devices).
4. Pushing a tag like `v2.1` automatically publishes a GitHub Release with the APKs attached.

### B. Azure DevOps
Push this folder to an Azure Repos repository and create a pipeline from `azure-pipelines.yml`.
The APK is published as the pipeline artifact **DischargeMedReminder-apk**.

### C. Android Studio
Open this folder in Android Studio (Ladybug or newer), let Gradle sync, then
**Build › Build App Bundle(s) / APK(s) › Build APK(s)**.

## Licence
MIT – see LICENSE. Third-party licences: THIRD_PARTY_NOTICES.md. Privacy: PRIVACY.md.
