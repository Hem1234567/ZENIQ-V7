# ZENIQ 1.3

* **Preview serves project files.** Images, fonts and other pages referenced with relative paths now load in the preview (only files inside the project folder are served).
* **Terminal.** `cd` now stays between commands, a **Stop** button cancels a running command, and **Run tests** / **Build log** buttons pick the right command for the project. Still a command-at-a-time shell, not a full PTY.
* **Real git** (JGit, no git binary needed): `git init`, `status`, `add`, `commit [-a] -m "msg"`, `log`, `branch` in the Code tab terminal, plus buttons. `.git` is hidden from the file tree and kept when restoring checkpoints.
* **Claude Code tab: real embedded terminal** (PTY + PRoot + Alpine + Node + the official Claude Code CLI), no Termux needed. See `docs/EMBEDDED-TERMINAL.md` and `THIRD_PARTY_NOTICES.md` (GPL components!).
* **Screenshot vision for Astra Control:** new `look_at_screen` tool (screenshot to an online vision model).
* Not verified: nothing here has been compiled or run on a device; see the test report in `docs/EMBEDDED-TERMINAL.md`.

# ZENIQ 1.2

* **Preview fixed.** Previews now open in their own full-screen screen with a real WebView (the old in-app dialog could stay blank on some phones).
  Website pages also got fallbacks for older WebViews (no `inset`, guaranteed reveal of content).
* **Code tab: Save & Share.** One tap packs the open project into a ZIP (also saved in Documents) and opens the share sheet; every project in the list has a *Share ZIP* button too.
* **Model loading fixed.** "Not enough RAM" was shown for every kind of failure (even when a load was simply already running) and the check looked at the
  Java heap instead of the phone's RAM. Now the real reason is shown, a smaller context is tried automatically before giving up, and big models get an honest warning.
* **Faster replies.** Greetings, the time, the date and simple sums are answered instantly with no model. Fast Mode (cloud, answers start in about a second) is now on by
  default and is used automatically as soon as an OpenRouter key is set; a cloud model that has not started within 12 s is skipped. Short questions get short answers.
* **Scheduled tasks.** "Every day at 8 am give me the weather": ZENIQ sends a notification at that time; tapping it runs the task. Manage them in Settings.

# ZENIQ 1.1

* Chat history: **search inside messages**, **pin**, **rename** and **export** a chat as Markdown.
* **Preview** button on HTML and SVG code blocks in answers (live, JavaScript on).
* **add_calendar_event** tool (opens the calendar with the event filled in), **home-screen widget** and **Quick Settings tile** "Ask ZENIQ".

# ZENIQ 1.0

* **Pictures in files.** Attach photos and say "add this image to a ppt about ...", "put these photos in a Word document", "add the picture to the
  Excel sheet" or "make a website with this photo". The app inserts the real pictures into Word, PDF, Excel, PowerPoint (one slide per picture,
  or where the AI places them with `![caption](image:1)`) and websites (hero and gallery). It works with local models, offline, and with cloud models.
* **Big files and big data.** Long documents are processed part by part: summaries are built from notes on every part, and "edit this file"
  on a file that is too long for one prompt applies your instruction to each part and saves a new file (tables keep their header in every part).
* **ZENIQ Code** (new Code tab): projects, file explorer, editor with tabs and syntax colours, find and replace, an AI that reads the whole project
  (Edit / Generate / Explain / Debug / Refactor) and proposes changes you accept or reject as diffs, a terminal, checkpoints (git-style history with
  restore), live Run/preview for web projects and ZIP export.
* **ZENIQ Control.** Every tool action is shown as a card with its result and a Stop button. Settings has an optional switch to pause for your
  approval before calls, SMS, shell commands and file writes (off by default).
* **Memory, projects and writing styles** (Settings): ZENIQ remembers facts ("remember that ...", "forget ..."), applies project instructions,
  and answers in the style you choose. **Regenerate** and **Edit my message** appear under the last answer.
* **Share to ZENIQ** from any app, and **search_web** (answers with a Sources list; research requests produce a structured report).
* **Fewer freezes.** The model runs at a slightly lower thread priority and with fewer threads, long answers are parsed off the UI thread,
  streaming updates are slower, unloading a model never waits on the UI thread, and fewer accessibility events are delivered.

# ZENIQ — Update 8 (this version)

* **Websites in one file, with 20 built-in designs.** "Create a website for my bakery" now builds ONE self-contained `.html`
  file (HTML + CSS + JavaScript together). The AI only writes the words (about 350 tokens, so it is quick even on the phone);
  the page itself comes from 10 normal and 10 premium designs with mobile menu, scroll animations, counters, gallery, reviews,
  contact form and, for premium: pricing, FAQ, process steps, call-to-action band and a light/dark switch.
  Say *premium / beautiful / professional* (or a colour such as blue or green) to choose the tier or the look; the design
  changes from one request to the next. Anything that really needs several files (React, back end, web apps, games) still goes
  to the normal app builder, which now also keeps small sites in a single `index.html`.
* **Preview.** The finished website appears as a card with a **Preview** button: a full-screen live preview (JavaScript on) with
  Reload, Code view and Open-in-browser, like an artifact preview.
* **Faster short answers.** Short questions are answered in 1-3 sentences (limit 200 tokens), so they finish in seconds; anything
  that asks for detail or writing gets the full length.

# ZENIQ — Update 7 (this version)

**Faster local answers.**
* The chat is now written to the model exactly as it was generated, so from the second message on the model only reads the *new*
  message instead of the whole conversation again (this was the biggest cause of slow replies in longer chats).
* More CPU cores are used for answering (4-6) and even more for reading the prompt; older turns are dropped sooner (last 8 messages).
* Answers are kept short by default (700 tokens max, "a few sentences" in the system prompt).
* For the quickest answers use a small model (Qwen3 0.6B / 1.7B, Q4) or turn on **Fast Mode** with an OpenRouter key: the first words
  then appear in about a second or two.

# ZENIQ — Update 6 (this version)

* **Fixed "ZENIQ isn't responding".** The model warm-up added in Update 5 ran on the UI thread and froze the app right after it
  started. It now runs in the background, takes the same lock as loading and answering (so two native calls can never overlap),
  and is skipped or cut short automatically if you ask something first. Generated pictures are also decoded off the UI thread now.

# ZENIQ — Update 5 (this version)

* **New name and logo.** The app is now **ZENIQ** everywhere (name, notifications, files, prompts) with the new launcher icon
  (adaptive, round, themed/monochrome) and a matching notification icon. The internal package id is unchanged so an installed
  copy updates in place.
* **No more Allow / Deny pop-up.** ZENIQ does what you ask on your own phone straight away, and it no longer answers with
  "I can't do that for security reasons".
* **`browser_script` tool.** The model writes a small JSON step list (`open`, `wait`, `type`, `click`, `press`, `scroll`,
  `extract`, `eval`, `show`) and the phone runs all of it in one go in the built-in browser; the final page is then opened in
  your own browser. "Open YouTube and search free fire" is also handled instantly without waiting for the model.
* **Images and video.** Say "generate one apple image" or "make a short clip for a thank-you screen" in the chat. Images use the free
  Pollinations service (no key) or any OpenRouter image model; videos use OpenRouter video models (key + credits).
  The new *Cloud models* section on the Models page lists paid GPT / chat models, image, video and any-to-any models and lets you pick one.
* **Cooler and faster.** The local model now uses only the fast CPU cores, the tool prompt is much shorter and only describes the tools
  the request needs, the start of the chat prompt is pre-loaded right after the model loads, and the streamed text updates the screen
  less often. Cloud (Fast Mode) answers start in about a second or two.
* **PowerPoint templates without stock photos.** All sample photos are removed from the six templates.

# ZENIQ — Update 4 (this version)

**Designed files.** Every PDF, Word, Excel, PowerPoint and text file now comes out with a real design instead of plain text.

* **PowerPoint** is filled into the bundled templates in `app/src/main/assets/templates/ppt/`
  (3 normal ones, 3 premium ones). Ask for a *premium / beautiful / professional* presentation and a premium template is
  used; ask for just a "ppt" and a normal one is used. The app remembers the last template and picks a different one next time.
  The engine clones the template slide that fits the content (cards, list, text), swaps the text, shrinks fonts to fit and
  drops the slides it does not need. If a template ever fails, the built-in plain layout is used, so a file is always made.
* **Word / PDF / Excel / text** use 6 colour themes (3 normal, 3 premium) with a title banner, themed headings, tables,
  header/footer and page numbers. Same rule: premium words choose a premium theme, and the theme rotates.
* **Bug fixed:** attaching a file and writing "summarize this" now summarizes the *new file*. Its text is placed inside the
  message itself and the earlier chat is hidden from the model for that turn.

Adding or changing a template: put the `.pptx` in the folder and run `tools/templates/prep.py`
(it trims unused slides, compresses pictures and regenerates `TemplateCatalog.kt`).

# ZENIQ

On-device AI agent for Android (llama.cpp + Qwen3) with an Apple-style UI.

## Update 3 (this version)
- **Code answers like Claude**: code always appears in a dark/light *code block* with syntax colours (keywords, strings, numbers, comments, functions, tags) and a **Copy** button. Normal answers render markdown (bold, lists, headings, tables) and also have a **Copy** button.
- **No web search for code**: if you ask for code (or to fix/explain code) ZENIQ skips every tool, so it never opens the browser or searches the web. The model writes the code itself (your local model, or the cloud model when Fast Mode is on). Code requests automatically get a larger answer budget and a lower temperature for accuracy.
- **Build a small app -> ZIP**: say *"build a small todo app"* / *"make a snake game"* / *"create a calculator website"*. ZENIQ writes the files, packs them into a ZIP and shows a card with **Download ZIP** and **Share**.
  Every ZIP is saved on the phone in one dedicated folder: `Documents/ZENIQApps/<project>.zip` (Files app -> Documents -> ZENIQApps). Web apps (index.html + css + js) run by simply opening `index.html`.
- **Apple-style attach menu**: the **+** button opens an iOS action sheet (Camera / Photos / Files) with coloured icon tiles and a Cancel button.

On-device AI agent (llama.cpp + Qwen3), Apple-style UI, live process cards, phone control, in-app browser.

## Get the APK from GitHub (no Android Studio needed)
1. Create a new GitHub repo and upload **the contents of this folder** (so `.github/`, `app/`, `build.gradle.kts` sit at the repo root).
   Using git:  `git init && git add . && git commit -m "ZENIQ" && git branch -M main && git remote add origin <your-repo-url> && git push -u origin main`
2. Open the **Actions** tab -> **Build ZENIQ APK** runs automatically (first build ~10-15 min).
3. Open the finished run -> **Artifacts** -> download **ZENIQ-APK-<run number>** -> unzip -> install `ZENIQ.apk`
   (allow "Install unknown apps").
4. Optional permanent download link: tag a release:  `git tag v0.1 && git push --tags`
   -> the APK appears under the repo's **Releases** page.

## First run
- The app reads your phone's RAM and suggests the best Qwen3 model; tap **Download**. It loads automatically.
- Settings -> enable the ZENIQ accessibility service (Android 13+: App info -> ⋮ -> Allow restricted settings).
- Optional: Settings -> Display over other apps.

## Build locally
Android Studio + NDK 26.1 + CMake 3.22.1, run `./setup.sh` once, press Run on an arm64 phone.
# ZENIQ-04



## What's new
- **Faster replies**: no-think mode for local Qwen3, tools only sent when the request needs them, instant "take a photo" path.
- **Fast Mode button** in the chat header (and in Settings). Live list of free OpenRouter models, automatic fallback if one is unavailable.
- **File attach like Claude**: the PDF shows above the prompt box and only enters the chat when you send. Real PDF text extraction (PdfBox).
- **Camera**: `take_photo` opens the camera and presses the shutter via the accessibility service.

## Update 2
- **Voice is OFF by default**; turn it on with the speaker button in chat or Settings > Voice (remembered).
- **Attach menu (+)**: Camera, Photos, Files. Images are read by a free cloud vision model (needs Fast Mode).
- **Left sidebar** (menu button or swipe right): New chat, recent chats, Show more -> full History screen with search and delete. Chats are saved and get an automatic title.
- **Settings > Storage & memory**: live app storage (models, chats, cache, APK) and live RAM use, plus Clear cache.
- **More phone tools**: web_search, youtube_search, maps_search, send_sms, call_number, set_alarm, set_timer, screenshot; the agent keeps going until the task is done. "Always allow" in the approval dialog removes repeated prompts.

## Update 3 — Documents: create, download and edit PDF / Word / Excel / PowerPoint
- **Ask for a file and get a real one.** "Create a PDF resume", "make an Excel budget with formulas", "Word report on solar energy", "PowerPoint on AI, 8 slides", or several at once ("PDF and Word and Excel"). The chat shows a card with **Open**, **Download** and **Share**.
- **Where files go:** every file is saved to `Documents/ZENIQFiles/` on the phone. **Download** puts another copy in `Downloads/ZENIQ/`. Open uses any PDF/Office viewer on the phone; Share opens the Android share sheet.
- **Upload and edit.** Attach (+ → Files) a `.pdf`, `.docx`, `.xlsx`, `.pptx`, `.csv`, `.txt` or `.md`, then say what to change ("fix the grammar", "add a Tax column", "translate to Hindi", "make slide 3 shorter"). ZENIQ writes a new `<name>-edited.<ext>` in the same format and keeps your original untouched.
- **Follow-up edits.** A file ZENIQ just created stays editable: "change the title", "add a conclusion", "now give me this as a PDF".
- **Convert without AI.** "Convert this PDF to Word", "save it as Excel/PDF/PPTX" is done instantly on the phone: no model, no internet, nothing rewritten.
- **Excel details:** one sheet per table, styled header, frozen top row, filters, number / % / currency formats, and formulas (`=SUM(B2:B6)`) that also store their computed result, so they show correctly even in phone previews.
- Old binary formats (`.doc`, `.xls`, `.ppt`) can't be read: save them as `.docx` / `.xlsx` / `.pptx` first.
- **Limits:** editing rewrites the whole document, so very long files need Fast Mode (about 18,000 characters) or a larger local context. Layout of an *uploaded* file (fonts, images, custom styles) is rebuilt in ZENIQ's clean style; text, headings, lists, tables and formulas are kept.
- No new libraries were added: Word/Excel/PowerPoint are written directly as Office files and PDFs use Android's built-in PDF engine, so the GitHub build is unchanged.
#   Z E N I Q - V 7  
 