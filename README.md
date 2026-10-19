# Sheaf

**A PDF toolkit that cannot upload your documents.**

Merge, split, compress, sign and read PDFs on your phone. Every tool is free. Nothing is sent
anywhere, and the reason to believe that is this repository rather than a privacy policy.

A sheaf is a bundle of papers held together, which is the thing the app makes.

## Why this exists

Every mainstream PDF app is a thin client for a server. iLovePDF, Smallpdf and Adobe all
upload the file, do the work in a datacentre and send the result back. The documents people
put through these apps are contracts, bank statements, medical reports, salary slips and
scans of identity cards — and they are uploaded to strangers to have two pages merged.

The second thing those apps have in common is a paywall on the second tool. The free merge
exists to sell you the split.

Sheaf does the work on the device. There is no server, so there is nothing to pay for, so
there is nothing to gate.

## Nothing leaves the phone

Sheaf does not declare the `INTERNET` permission, so the process cannot open a socket. Your
documents cannot leave the device even if a dependency tried to send them, and that is
checkable on the Play Store listing without taking anyone's word for it.

`app/src/main/AndroidManifest.xml` strips network access explicitly:

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
```

Libraries declare permissions of their own and the manifest merger unions them into the app,
so these two lines remove network access no matter what any current or future dependency asks
for. **Never resolve a merger conflict by deleting them.** Verify after a build in
`app/build/outputs/logs/manifest-merger-*-report.txt`.

Four consequences that shape the code:

- **No crash reporting.** Sending a crash report needs the network. The About screen offers to
  compose an email instead, which the user sends themselves from their own mail app.
- **No ML Kit Document Scanner.** It pulls its module through Play services. The scanner in P2
  is built here instead.
- **OCR will use ML Kit's *bundled* recogniser**, not the unbundled Play-Services-backed one —
  the same call Deja and Abhyas made.
- **No storage permission either**, and specifically never `MANAGE_EXTERNAL_STORAGE`. Files
  come in through the Storage Access Framework and go out through `ACTION_CREATE_DOCUMENT`.

Opening a link from the About screen does not contradict any of this: Sheaf hands a URL to
another app through an Intent and that app does the fetching under its own permissions.

## Sheaf never writes to your original file

Not for a quick rotate, not to save a round trip, not ever.

The rule is structural rather than a convention somebody has to remember. `DocumentStore`
copies the document you picked into Sheaf's own cache and hands back a `SheafFile` pointing at
the copy. Every operation in the app takes a `SheafFile`, so an operation can only ever reach
the copy. The only component permitted to open a `Uri` for writing is `Exporter`, and the only
`Uri` it may write to is a new document you just created in the system picker.

If you are ever tempted to add a `contentResolver.openOutputStream(source.uri)` anywhere else
in this codebase, that is the bug.

## One shape for every tool

```
List<SheafFile> -> Op -> List<SheafFile>
```

Plural on both sides from the first commit, and it is the most consequential decision here.
Merge is many-to-one, split is one-to-many, compress is one-to-one, and a batch is the same
operation handed more inputs — so batch mode is not a feature to build later, it is what the
type already means. Chaining tools into a saved preset is then function composition rather
than new machinery.

The competition sells "process twenty files at once" as the paid tier. Here it falls out of
the signature. Resist any pull towards a convenient `fun run(input: SheafFile): SheafFile`.

## What it does

| Tool | What it does |
|---|---|
| Read | Open a document, search it, read it aloud with the phone's own voice, see its comments, then hand it to any tool |
| Scan a document | Camera capture, corner adjustment, document filter |
| Make searchable | OCR a scan into an invisible, selectable text layer; the layer carries any script, through a glyphless font, and each line is stretched to its ink |
| Key points | The sentences that carry a document, and the amounts, dates and percentages in it, picked on the phone; every point is the document's own sentence |
| Compare two versions | Older and newer: new words in green on the newer file, a note where words went |
| Edit text | Tap any line - cell by cell in a table - and change it: painted over in the page's own paper and ink, in its own font where it has the letters, squeezed to fit; scans are read first and only changed words repainted; find and replace across every page |
| Edit pictures | Move, resize, take out or swap the pictures already on a page, nothing else touched |
| Resize photo to KB | A photo or signature under 20 KB, 50 KB or any size a form asks, as a passport photo or exact pixels, with location and camera details left out |
| Rent receipts | A year of HRA receipts from what you type, amount in words, two to a page |
| Extract text | Pull the words out as plain text |
| Merge | Join several PDFs, in an order you drag them into |
| Split | Every N pages, single pages, by ranges, or at the document's own bookmarks |
| Extract or remove pages | Keep only the pages you name, or take those pages out |
| Insert or replace pages | Put another PDF, or blank pages, in at a chosen page, or in place of pages you name |
| Organise pages | Thumbnail grid: reorder, rotate, delete |
| Page labels | What a reader calls each page: i, ii, iii for the front matter, then 1, 2, 3, with a prefix if you want |
| Bookmarks | Edit the table of contents a reader shows: titles, pages, nesting |
| Images to PDF | A4, Letter, A5 or fit-to-image, with margins; one PDF or one per image |
| PDF to images | PNG or JPEG at 96, 200 or 300 DPI |
| PDF to Word, Excel, PowerPoint, EPUB or web page | Paragraphs, headings, lists, tables and pictures rebuilt from the pages, as .docx, .xlsx, .epub or .html; a .pptx with a slide per page, as pictures or editable text |
| Word, Excel or text to PDF | A .docx, .xlsx, .txt, .md or .csv laid out on A4, in the phone's own fonts for Indian scripts |
| Compress | Three levels, or a size to get under - with pages made pictures, their words kept underneath, if that is what it takes - and it tells you when there was nothing to gain |
| Attachments | Put files inside a PDF, or take out the ones already there |
| Digital signature | A PKCS#7 signature from your own .pfx or .p12, visible or not, the kind every reader's panel checks |
| Archive copy (PDF/A) | A PDF/A-2b copy: sRGB profile built in, XMP, file ID, no scripts, attachments or password |
| Add a password | AES-256, with the printing and copying flags as a stated request; or restrict only, with no password to open |
| Remove a password | For a document whose password you know |
| Watermark | DRAFT or a name in any script, once or repeated, grey, red or blue, sized to the page, on the pages you choose |
| Page numbers | Six positions, five formats in any script, skip a cover, on a white plate that keeps them readable on a phone photo; Bates numbers with a prefix that run on across files |
| Sign | Draw a signature and flatten it into a page |
| Mark up | Highlight a line or draw on a page with your finger |
| Add text | Tap a page and write on it in any script, shaped by the phone's own text engine, covering what is there if you need to |
| Add an image | A logo, a stamp or a photo on a page, drawn in so it cannot be dragged off |
| Create a form | Drag text boxes, paragraph boxes, tick boxes and drop-downs onto a page; anyone can fill the result |
| Fill in a form | Type into a real PDF form, keep the answers for next time, and seal them so they cannot be retyped |
| Header and footer | A line along the top or bottom of every page, in any script, with {page}, {total}, {date}, {name} |
| Crop pages | Millimetres off each edge, or the white margins found automatically, then A4, Letter, A5, Legal or A3 |
| Page background | A colour behind the pages, under the text |
| Pages per sheet | 2, 4, 6, 8, 9 or 16 pages on A4, Letter, A3 or Legal, turned whichever way shows them biggest |
| Split by size | Parts that fit a 2, 5, 10 or 25 MB limit, or any size you type, each measured by saving it |
| Extract images | The embedded originals, not re-rendered pages |
| Remove areas | Genuine removal - see below - and Find and mark for Aadhaar, PAN, phone, email and card numbers |
| Document details | Edit the title, author, subject and keywords - for several files at once, an empty box keeps each file's own; blank them; or clean out everything hidden - XMP, scripts, attachments, comments, thumbnails, private data - and say what was found |
| Recipes | Your own chain of steps, set up with each tool's own options, kept on the phone and run on any number of PDFs in one tap |

**Open a file** takes anything - PDFs, photos, Word or Excel, a whole folder - looks at it on the phone and offers the tools that fit, best first, with the reason in words: a scan, a form, a statement full of amounts, an Aadhaar or PAN on the page, a locked file. Every tool that takes several files reorders them by dragging and can take a whole folder; pages being worked on zoom with two fingers.

### The viewer is a reader, not a preview

Pinch and double-tap zoom that **re-renders** rather than scaling the bitmap, so type stays
sharp - the page cache keys on index and width so both sizes can live in it. Find in document
with the matches boxed on the page and arrows that step through them. The document's own table
of contents, and the reader's own marked pages beside it. It reopens where you left off. A page
counter you tap to jump, a night mode that inverts the page at draw time, keep-the-screen-on,
share, and print - which is also every Android device's save-as-PDF, and works with no INTERNET
permission because the system's print service does the talking.

Everything in it can be handed straight to any tool without going back through the picker.

Every one of them runs as background work behind a foreground service, so a long job survives
the user switching apps.

### Tools chain

A finished result has a **Then…** button that hands it straight to the next tool. Merge, then
compress, then add a password: three taps, with nothing saved to Files and picked back up in
between. That falls out of the `List<SheafFile> -> Op -> List<SheafFile>` signature rather than
being new machinery, which is the whole reason the signature is plural.

Sharing several PDFs into Sheaf from another app lands them all in Merge, because a viewer can
only show one of them and merging is the obvious thing to want with five.

Reading and the tools are one flow, not two. Open a document, read it, and hand it straight to
any tool from the viewer's own bar - the tool receives the same file without sending you back
to the system picker to find it again. That is what stops the viewer being a dead end and what
gives the recents list a job beyond nostalgia.

### Removing something means removing it

A black rectangle drawn over text leaves the text in the file, selectable and copyable by
anyone who opens it in a different reader. That is how redaction failures reach the news, and
it is what most "redact" buttons actually do.

**Remove areas** renders the marked pages to an image with the boxes painted on, and that
image replaces the page. The text underneath is not covered — it is gone, because it was never
written into the new page at all.

The cost is real and the UI says so: a redacted page becomes a picture, so its text stops
being selectable and searchable, and the file grows. Only the marked pages are treated this
way; every other page keeps its text untouched.

**Marks are drawn into the page, not attached to it.** A PDF annotation is an object the next
reader's app can move, hide or delete; a highlight somebody made should still be there when the
document is opened somewhere else. The cost is that marks cannot be peeled off again, and the
UI says so - the file you chose is never changed either way.

**Add text is a correction, not a redaction either.** Covering the old words paints paper over
them; they are still in the file underneath. It is the right tool for a wrong date and the
wrong one for a bank account number.

**Crop is not redaction, and does not pretend to be.** Cropping changes the CropBox: what falls
outside stops being displayed but is still in the file, and comes back if someone crops the
other way. The crop tool says this on screen and points at Remove areas.

## Project layout

One module, like Deja and Abhyas. LayerLink split out `:core` because two apps shared a
screen-capture engine; Sheaf has no sibling to share with, so the boundaries that matter are
enforced by package discipline instead of by Gradle. Split `:core-pdf` out the day build times
hurt, not before.

```
app/src/main/java/com/layerbit/sheaf/
  pdf/      PdfEngine — reading and rasterising; PdfSurgeon — restructuring and writing
            PlatformPdfEngine — android.graphics.pdf, the viewer's renderer
            PdfBoxSurgeon / PdfBoxEngine — PDFBox, for writing and for encrypted documents
            PageSelection — "1-3, 7, 12-" to page indices, one-based in, zero-based out
            BitmapBudget — the render clamp that keeps large documents from OOMing
  files/    DocumentStore — the only way a document enters the app
            Workspace — scratch space for operation output, with eviction
            Exporter — the only component that may write to a Uri
  ops/      Op — the one operation shape; ToolId — every tool, in one list
  jobs/     JobRunner + OpWorker — every operation runs as WorkManager work
  data/     Room: recents, reading positions, marked pages - nothing read out of a document
  prefs/    SharedPreferences: the theme and the handful of choices the user makes
  ui/       Compose screens; theme/ holds the palette and Space Grotesk
```

### The `PdfEngine` seam

There is no single Android PDF library that does everything Sheaf needs at a licence it can
use. The platform renderer is free and reliable but cannot restructure a file; PDFBox can
restructure anything but is slow and memory-hungry to render with; the libraries that do both
well are AGPL and therefore unusable in a closed Play app. So the app owns the interface and
the libraries are implementation details behind it.

The split is along the same line: `PdfEngine` reads and rasterises, `PdfSurgeon` restructures
and writes. The viewer runs on the platform renderer because it is fast and allocates little;
every operation runs on PDFBox. A third implementation, `PdfBoxEngine`, exists for the one
thing the platform renderer cannot do at all — open an encrypted document given its password —
and the viewer picks it up through the same interface without knowing anything changed.

**Nothing outside the `pdf` package may import a PDF library type.** If a caller needs
something the interface cannot express, widen the interface — never reach around it.

### Licensing is the constraint that decides this project

| Library | Licence | Role |
|---|---|---|
| `android.graphics.pdf` | AOSP | The viewer's renderer — fastest, allocates least |
| `com.tom-roush:pdfbox-android` | Apache-2.0 | Everything structural, behind `PdfSurgeon` |
| `com.google.mlkit:text-recognition` | Proprietary, bundled model | OCR - the BUNDLED build, Latin and Devanagari |
| `org.bouncycastle:bcpkix-jdk18on` | MIT-style | The PKCS#7 signature behind Digital signature |
| `androidx.camera:*` | Apache-2.0 | The scanner's viewfinder |

**Never add iText, MuPDF or Ghostscript.** All three are AGPL. Ghostscript is the obvious
answer for compression and is the one most likely to be reached for by accident.

## Roadmap

| Phase | Ships as | What it is |
|---|---|---|
| **P0** | — | ✅ Foundation. Engine seam, file layer, job runner, viewer. |
| **P1** | 0.1.0 | ✅ The nine tools above. |
| **P2** | 0.2.0 | ✅ Scanner, OCR, in-document search, outline, night mode. |
| **P3** | 0.3.0 | ✅ Watermark, page numbers, sign, crop, redact. |
| **P4** | 0.4.0+ | ✅ N-up, split by size, extract images, metadata. |
| **P5** | 0.5.0 | ✅ Reader-grade viewer, forms, insert, header/footer, settings, light theme. |
| **P6** | 0.6.0 | ✅ The Foolscap set: page labels, bookmarks, attachments, background, clean hidden data, restrict-only, replace pages, measured split by size, millimetre and automatic crop, n-up grids, Bates across files, ZIP export. |
| **P7** | 0.7.0 | ✅ Foolscap's October set and past it: Edit text, Edit pictures, Resize photo to KB, Key points, Recipes, Open a file, every script on the page, zoom in the editors, drag to order, compress through pictures. |

Each phase has an exit gate. P0's was: open a 500-page document, scroll it end to end at 60 fps,
close it, and return to a flat heap. P1's is: every tool produces a file that opens correctly in
another reader, and a batch with one unreadable file still finishes the rest.

## Requirements

- Android Studio with an SDK for **compileSdk/targetSdk 36**
- **JDK 21 to run Gradle**, targeting Java 17 bytecode — see Building, the two are not the same
- `minSdk` is 26, and API 28 or newer on the device that generates the baseline profile

Toolchain: Gradle 8.14.5, AGP 8.13.2, Kotlin 2.2.21, KSP 2.2.21-2.0.5. KSP is what pins
Kotlin — Room needs it, and it had no release for Kotlin 2.3 or 2.4 when this was last moved.

**The next library upgrade is an AGP 9 migration, not a version bump.** Compose 1.12, core
1.19, lifecycle 2.11 and navigation 2.10 all declare `requires Android Gradle plugin 9.1.0 or
higher` in their AAR metadata, which is a hard refusal rather than a warning, and AGP 9 wants
Gradle 9 with it. The versions pinned here are the last ones on the near side of that line.
Crossing it is worth doing deliberately, with a device to test on, and not as housekeeping.

## Building

```
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The two commands above are run and green: a debug APK builds and the unit tests pass. A
release build — R8, the resource shrinker and the bundle — has not been put through its paces
as often, and it is the one that fails differently from debug, so cut one before trusting a
shrinker rule.

**Gradle needs JDK 21.** Not 17, which is only what the compiler targets, and not 25: the
Kotlin DSL in the 8.11.1 wrapper cannot parse that version string and dies with a bare
`IllegalArgumentException: 25.0.3` that names no file and never mentions Java. Android
Studio's own bundled runtime is 25, so set `JAVA_HOME` to a 21 before running `gradlew` from
a terminal.

**Generating the baseline profile** needs a device or emulator on API 28 or newer, plugged in:

```
./gradlew :app:generateBaselineProfile
```

It writes `app/src/main/generated/baselineProfiles/baseline-prof.txt`, which is checked in and
packaged. No release build ever runs it — a release that needs a phone attached is a release
nobody can cut. Read `StartupProfileGenerator`'s own comment first: the Gradle task fails on
Android 15 even when the run succeeds, and the four commands that get the file out by hand
are written down there.

**Whether that profile is worth anything has not been measured.** It is generated, it reaches
`assets/dexopt/baseline.prof` in the release APK, and ProfileInstaller installs it — but a
hand-timed cold start was the same with it and without. `StartupBenchmark` exists to settle
it properly, comparing `CompilationMode.None` against `Partial` over ten cold iterations:

```
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
```

Until someone runs that and writes the number down, treat the startup win as unproven.

## Icon

The mark is five paper leaves fanned from a common base and cinched by one band — a bundle of
documents, and a sheaf in the older sense of the word.

It is laid out on the 108dp adaptive-icon grid and scaled so the furthest leaf corner sits
31.9dp from centre, inside the 33dp safe radius, so nothing clips under a circular, squircle or
teardrop mask. Changing the rotations or the leaf height without redoing that arithmetic will
push corners outside the safe zone. Source lives in `app/src/main/res/drawable/`, with a
monochrome layer for Android 13 themed icons; `store-assets/` holds the vector the Play
Console's 512px PNG is generated from.

---

From [Layerbit AI](https://layerbit.co.in).
