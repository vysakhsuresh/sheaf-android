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

## Project layout

One module, like Deja and Abhyas. LayerLink split out `:core` because two apps shared a
screen-capture engine; Sheaf has no sibling to share with, so the boundaries that matter are
enforced by package discipline instead of by Gradle. Split `:core-pdf` out the day build times
hurt, not before.

```
app/src/main/java/com/layerbit/sheaf/
  pdf/      PdfEngine — the seam every PDF library sits behind
            PlatformPdfEngine — android.graphics.pdf, the P0 implementation
            BitmapBudget — the render clamp that keeps large documents from OOMing
  files/    DocumentStore — the only way a document enters the app
            Workspace — scratch space for operation output, with eviction
            Exporter — the only component that may write to a Uri
  ops/      Op — the one operation shape; OpRegistry — every tool, in one list
  jobs/     JobRunner + OpWorker — every operation runs as WorkManager work
  data/     Room: a recents list, and nothing read out of a document
  ui/       Compose screens; theme/ holds the palette and Space Grotesk
```

### The `PdfEngine` seam

There is no single Android PDF library that does everything Sheaf needs at a licence it can
use. The platform renderer is free and reliable but cannot restructure a file; PDFBox can
restructure anything but is slow and memory-hungry to render with; the libraries that do both
well are AGPL and therefore unusable in a closed Play app. So the app owns the interface and
the libraries are implementation details behind it.

P0 ships exactly one implementation, on `android.graphics.pdf`. That is deliberate: it proves
the seam with zero third-party surface, and gives every later engine a working reference to be
checked against.

**Nothing outside the `pdf` package may import a PDF library type.** If a caller needs
something the interface cannot express, widen the interface — never reach around it.

### Licensing is the constraint that decides this project

| Library | Licence | Role |
|---|---|---|
| `android.graphics.pdf` | AOSP | Rendering and measurement — the P0 engine |
| `com.tom-roush:pdfbox-android` | Apache-2.0 | Structure: merge, split, encrypt, forms (P1) |
| `com.google.mlkit:text-recognition` | Proprietary, bundled model | OCR (P2) |

**Never add iText, MuPDF or Ghostscript.** All three are AGPL. Ghostscript is the obvious
answer for compression and is the one most likely to be reached for by accident.

## Roadmap

| Phase | Ships as | What it is |
|---|---|---|
| **P0** | — | Foundation. Engine seam, file layer, job runner, viewer. |
| **P1** | 0.1.0 | The core eight: merge, split, organise, images↔PDF, compress, password. |
| **P2** | 0.2.0 | Scanner, OCR, in-document search, bookmarks. |
| **P3** | 0.3.0 | Annotate, fill and sign, watermark, page numbers, redact. |
| **P4** | 0.4.0+ | Pipelines and presets, N-up, compare, split by size. |

Each phase has an exit gate. P0's is: open a 500-page document, scroll it end to end at 60 fps,
close it, and return to a flat heap.

## Requirements

- Android Studio with an SDK for **compileSdk/targetSdk 36**
- JDK 17
- `minSdk` is 26

## Building

```
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

**Not yet built end to end.** The environment this was written in had no Android SDK and no
access to Google's Maven repository, so Gradle could not be run. The source was written and
reviewed by hand against the AndroidX, Compose and `android.graphics.pdf` APIs. Open the
project in Android Studio and sync to pull dependencies and catch anything environment
specific — expect to fix a version or an import on the first sync.

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
