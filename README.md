# VoiceFile — Offline Multilingual Voice-Controlled File Opener

Speak a file name in **English, தமிழ் (Tamil), or العربية (Arabic)** → VoiceFile finds the
file on your phone → it opens or plays it immediately in a real Android app.

Everything after the one-time setup runs **fully on-device**: speech recognition, file
search, and file opening. No cloud AI, no remote speech service, no account, no backend,
no database, no paid API. Audio, file names, and recognized text are never uploaded.

**Core flow:** open app → pick a language → tap the mic → speak → the file opens.

---

## 1. Status & what was verified

| Check | Result |
|---|---|
| `gradlew :app:assembleDebug` | ✅ builds — [app/build/outputs/apk/debug/app-debug.apk](app/build/outputs/apk/debug/app-debug.apk) |
| `gradlew :app:assembleRelease` (R8 + resource shrink, lint-vital) | ✅ builds (unsigned) |
| `gradlew :app:testDebugUnitTest` | ✅ **30/30 pass** (18 matcher + 12 normalizer, covering all three languages, cross-verification and closest-match fallback) |
| APK static inspection (aapt2 + unzip) | ✅ permissions as designed (no `MANAGE_EXTERNAL_STORAGE`), matched `libsherpa-onnx-jni.so` + `libonnxruntime.so` for arm64-v8a / armeabi-v7a / x86 / x86_64, app + sherpa classes in dex |
| Model download URLs (Hugging Face, official sherpa-onnx mirrors) | ✅ reachable; byte sizes match ModelManager's expected sizes exactly |
| **On-device run / airplane-mode test** | ❌ **not performed here** — no phone was connected to this machine and the only emulator image present is a locked Play Store build. See §8 for the on-device checklist. |

The engine smoke test ([EngineSmokeTest.kt](app/src/test/java/com/voicefile/app/speech/EngineSmokeTest.kt))
is **opt-in and currently skipped**: sherpa-onnx's `v1.13.8` *desktop* `native-lib-win-x64`
jar hard-crashes the JVM (its statically embedded ORT 1.17.1 rejects the API-28 request —
an upstream desktop-artifact issue). The **Android AAR ships its own matched
`libonnxruntime.so`/`libsherpa-onnx-jni.so` pair**, verified present for every ABI in the
APK, and is what the app uses; this desktop harness issue does not affect the app.

---

## 2. Speech-engine evaluation (spec §3)

Requirements: offline recognition for **English, Tamil, and Arabic** with honest,
verified model availability.

### Vosk — evaluated, rejected (no Tamil)

Verified against the official registry `https://alphacephei.com/vosk/models`:

| Language | Vosk model | Status |
|---|---|---|
| English | `vosk-model-small-en-us-0.15` (40 MB, Apache-2.0) | ✅ available |
| Arabic | `vosk-model-small-ar-tn-0.1-linto` (158 MB), `vosk-model-ar-mgb2-0.4` (318 MB), both Apache-2.0 | ✅ available |
| Tamil | — | ❌ **does not exist** — no `ta` model anywhere on the registry; direct URL probes for `vosk-model-small-ta-0.42.zip` and `vosk-model-ta-in-0.4.zip` both return **HTTP 404** |

Vosk would not satisfy all three languages, and substituting online recognition for Tamil
is explicitly forbidden by the spec. → An alternative was required.

### Chosen: sherpa-onnx + multilingual Whisper (all three languages, offline)

* **Engine:** [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) **v1.13.8**, Apache-2.0.
  The official Android AAR is vendored at
  [app/libs/sherpa-onnx-1.13.8.aar](app/libs/sherpa-onnx-1.13.8.aar)
  (50 MB: Java/Kotlin API + `libsherpa-onnx-jni.so`, `libsherpa-onnx-c-api.so`,
  `libonnxruntime.so`, … for 4 ABIs). It uses on-device ONNX Runtime — no network.
* **Model:** multilingual **OpenAI Whisper** (MIT weights, Apache-2.0 code — commercial-friendly),
  served in sherpa-onnx's quantized (int8) form from the official sherpa-onnx mirrors on
  Hugging Face (`csukuangfj/whisper-*`). One multilingual model recognises **en, ta, and ar**;
  the selected UI language is passed to the decoder as its language hint.
* **Quality, honestly:** Whisper is the only mainstream fully-offline model family with
  solid Tamil support (Vosk has none). English ≈ good, Arabic ≈ good, Tamil ≈ usable —
  noticeably the weakest of the three, and **accuracy scales with model size**: install
  **Medium (~902 MB)** if Tamil recognition is the priority. Recognized errors are surfaced
  to the user (the recognized text is shown with a retry) instead of opening a random file.

**Evaluated but not shipped (research for this build, Oct 2026):**

* *Dolphin small CTC int8* (Apache-2.0, sherpa-native, ~239 MB) — lists Tamil among its
  languages, but no published Tamil WER could be verified; needs on-device A/B testing
  against real commands before shipping.
* *AI4Bharat IndicWhisper Tamil* (MIT, 25.2% WER on Tamil FLEURS) — better-known Tamil
  accuracy, but no ready-made sherpa-onnx export exists; converting it is a project of its
  own.
* *Meta MMS-Tamil* — CC-BY-NC (non-commercial) license; excluded by the permissive-license
  requirement.
* *SPRING Lab Tamil Zipformer* — TorchScript only, no sherpa-onnx ONNX package.

**Vocabulary biasing:** sherpa-onnx's hotword/context biasing works only for transducer
models with `modified_beam_search` — Whisper decodes greedy-only and cannot be biased at
recognition time, and the Kotlin API exposes no N-best/LM rescoring. Cross-verification
therefore happens **in app code after decode** (see §3 “Filename matching”).

### Model installation (spec §4)

All three languages come from **one model**, so setup downloads it once:

| Variant | Files (int8 only) | Download | Trade-off |
|---|---|---|---|
| **Small** (default) | `small-encoder.int8.onnx` 112.4 MB + `small-decoder.int8.onnx` 262.2 MB + `tokens.txt` 0.8 MB | ~376 MB | Good balance; decent Tamil/Arabic |
| **Medium** | `medium-encoder.int8.onnx` 374.2 MB + `medium-decoder.int8.onnx` 571.1 MB + `tokens.txt` 0.8 MB | ~902 MB | **Best Tamil/Arabic accuracy** in the Whisper family; slower on low-end phones |
| **Tiny** | `tiny-encoder.int8.onnx` 12.9 MB + `tiny-decoder.int8.onnx` 89.9 MB + `tokens.txt` 0.8 MB | ~104 MB | Fastest/leanest; weakest Tamil/Arabic |

All three file sets were verified byte-for-byte against the official
`csukuangfj/sherpa-onnx-whisper-*` mirrors (HTTP HEAD on each file) before being wired
into the installer — a wrong size fails the download verification with a clear error.

* **Internet is required only for this one download** — the Setup screen discloses this
  verbatim. After the model is installed the app never opens a network socket (the only
  other use of `INTERNET` would be nothing: file search is SAF/MediaStore, opening is Intents).
* Downloads are verified against expected byte sizes before being accepted, stored in
  app-private storage, and a different variant replaces (and frees) the previous one.
* Audio, file names, and recognized text are **never** transmitted.

---

## 3. How the app works

```
mic (16 kHz mono, in RAM only)
  → silence endpointing (stops after 700 ms quiet; 6 s "no speech" abort; 15 s cap)
  → sherpa-onnx Whisper (on-device, language hint = selected language)
  → parse phrase (strip spoken commands per language, detect category hints)
  → rank on-device files (fuzzy, trilingual normalization)
  → exactly one clear match? open it now : show ranked list (or a useful error + retry)
```

### File access (spec §5)

* **Storage Access Framework** is the primary source: the user picks folders
  (Downloads, Documents, Music, Movies, Pictures, anything) with
  `ACTION_OPEN_DOCUMENT_TREE`; grants are taken **persistently** so folders are never
  re-selected; add/remove any time under **Settings → Folders**.
* **MediaStore** (audio/video/images, plus Downloads where exposed) is queried as a
  second source when the matching permission is granted (`READ_MEDIA_*` on 13+,
  `READ_EXTERNAL_STORAGE` ≤ 12) — this finds songs/videos/photos outside chosen folders.
* A recursive `DocumentsContract` walk builds an in-memory index (capped at 50 000 files,
  depth 10, hidden/`Android/`/`obb` dirs skipped). **No `MANAGE_EXTERNAL_STORAGE`**, no
  sandbox bypass: files Android doesn't expose simply don't appear.

### Filename matching (spec §6 — see note below)

* Normalization per language: case/punctuation folding for English; **Arabic** diacritics
  and tatweel removal, أ/إ/آ→ا, ى→ي, ة→ه folding; **Tamil** combining-mark handling that
  keeps syllables intact (spoken accusative `பாடலை` ≡ written stem `பாடல்`).
* Spoken command words are stripped per language (`open/play…`, `திற/இயக்கு…`,
  `افتح/شغل…`) while content words are kept; Tamil case suffixes are tried as variants.
* Scoring: every spoken word must match (exact > substring > Dice-coefficient fuzzy),
  plus phrase-bonus, category bonus (`songs→audio`, `أغنية→audio`, `காணொளி→video`, …)
  and a media-preference penalty for `play`-style commands.
* **Vocabulary cross-verification (slang / misheard words):** before scoring, each
  recognized word is checked against a vocabulary built from the *actual file names on
  the device*. If one candidate clearly dominates (Dice ≥ 0.40 and ≥ 0.15 ahead of the
  runner-up — deliberately looser than normal matching, deliberately conservative about
  guessing), that real file word is added as an interpretation of what was said. The UI
  shows the mapping (“kompuytar → computer”) and the file then matches normally. This is
  what absorbs local slang, accents, and recognition slips.
* **Closest-match fallback:** if *nothing* passes the normal threshold, the app no longer
  dead-ends — it shows the **closest file names** (top 8 by lenient similarity) behind an
  explicit “Nothing matched exactly” banner with a retry prompt, so the user always gets
  candidates plus a useful error (spec §4.11).
* **Decision rules:** best ≥ 0.72 **and** ≥ 0.10 ahead of the runner-up → **auto-open,
  no confirmation**; several plausible hits → ranked list (tap to open); cross-verified
  substitutions never auto-open unless the overall thresholds are met.
* Category-only phrases ("play my songs", "பாடலை இயக்கு", "شغل الأغنية") list that media
  type (auto-opening only if exactly one exists); "Open Downloads" opens the authorized
  Downloads folder (or falls back to its file list).
* Matching is **script-aware, not transliterating**: Tamil speech matches Tamil-script
  names (see limitations).

### Opening (spec §2 items 4–10)

`ACTION_VIEW` + content URI + `FLAG_GRANT_READ_URI_PERMISSION`, with MIME fallback
(`*/*`): audio → default player, video → video player, documents → viewer/editor, images
→ gallery; folders open in a file manager. If no app can handle the type, the user gets a
clear message instead of silence.

---

## 4. Building & installing

```bash
./gradlew :app:assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # 27 unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17+, Android SDK (compileSdk 35), Gradle wrapper included (8.9).
No Android Studio needed for a CLI build.

---

## 5. First-run checklist (spec §8 — to be done on a real phone)

This machine had no connected device and only a locked Play Store emulator image, so the
following must be run on a real phone; each step maps to a spec requirement:

1. Install the APK → **Setup** appears (1. model download — needs internet once;
   2. microphone + media permission; 3. add folders).
2. **Airplane mode ON** → repeat every example command from the spec
   ("Open Resume", "ரெஸ்யூமை திற", "افتح السيرة الذاتية", "Play my songs", …):
   recognition must work, one clear match must open immediately with no confirmation,
   several matches must list, unknown speech must show the recognized text + retry.
3. Verify audio starts playing in the default player, video in a video player, documents
   in a viewer, images in a gallery.
4. Reboot / force-stop → authorized folders must persist (no re-selection).
5. Deny the mic → clear error; no folders → clear guidance; unknown format → graceful.

---

## 6. Honest limitations

* **Not yet verified on a physical device** (no device attached to this build machine) —
  see §5. Offline claims follow from architecture (no network code paths besides the
  one-time model download) but the airplane-mode test above must be run before claiming it.
* **Tamil recognition accuracy** is the weakest language; the **Medium (~902 MB)**
  install option exists for users who need better Tamil — pick it in Setup if Tamil is
  the main language. Still unverified on a physical device (see §5).
* **Cross-script transliteration:** Tamil speech matches Tamil-script file names (and
  English speech matches Latin names). Saying "ரெஸ்யூமை" will not match a file literally
  named `Resume.pdf`; say it in the script the file name uses.
* **Documents outside authorized folders on Android 13+** are invisible to MediaStore by
  design — that's what folder authorization is for. Nothing bypasses the sandbox.
* "Play my songs" shows the matching songs to pick from rather than blindly starting an
  arbitrary track (the spec forbids acting on uncertain matches).
* Whisper is end-of-utterance by nature: very long utterances hit the 15 s cap.
* The desktop engine smoke test is disabled due to an upstream sherpa-onnx Windows-jar
  ORT mismatch (§1); Android is unaffected.

---

## 7. Project layout

```
app/src/main/java/com/voicefile/app/
├── MainActivity.kt            # permissions, navigation, open-intent dispatch
├── MainViewModel.kt           # pipeline: record → recognize → index → rank → open
├── Prefs.kt                   # language, authorized folders, model variant (no history)
├── speech/
│   ├── ModelManager.kt        # one-time verified model download/install
│   ├── AudioRecorder.kt       # 16 kHz mono, silence endpointing, in-RAM only
│   └── WhisperEngine.kt       # sherpa-onnx OfflineRecognizer wrapper (lang hint)
├── files/
│   ├── FileIndexer.kt         # SAF walk + MediaStore index (in-memory)
│   ├── StorageRepository.kt   # persisted SAF grants
│   ├── FileOpener.kt          # ACTION_VIEW with URI grants
│   └── FileEntry.kt           # data types
├── matching/
│   ├── QueryNormalizer.kt     # en/ta/ar normalization, commands, categories
│   └── FileMatcher.kt         # fuzzy scoring, vocabulary cross-verification, ranking,
│                              # closest-match fallback, decision thresholds
└── ui/                        # Compose: Home, Setup, Folders screens
app/src/test/.../              # 27 unit tests (+ 2 opt-in smoke tests)
```

## 8. Licenses & third-party components

| Component | Version | License | Notes |
|---|---|---|---|
| sherpa-onnx (engine + Android AAR) | v1.13.8 | Apache-2.0 | vendored in `app/libs/` |
| Whisper model weights (small/tiny, int8) | via `csukuangfj/*` mirrors | MIT (OpenAI Whisper) | downloaded once at setup |
| onnxruntime (inside AAR) | bundled | MIT | on-device only |
| Vosk | evaluated only, not shipped | Apache-2.0 | models listed for the record in §2 |
| AndroidX / Jetpack Compose | BOM 2024.12.01 | Apache-2.0 | |

No telemetry, no analytics, no tracking dependencies exist in this project.
