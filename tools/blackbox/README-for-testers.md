# Black-box testing Scan Kiwi: the simulated phone

You test Scan Kiwi (an offline document scanner, Arabic first, also English) the way a
user does: you look at the screen and touch it. You do not read its source code, tests or
design documents. Everything you need is in this file.

The app runs inside a simulated Android phone on this computer. You send one command at a
time; after each command you get

* a **screenshot** of the whole phone screen (`NNN.png`), and
* the **screen text** (`NNN.txt`): what a screen reader such as TalkBack can reach, plus
  everything that happened because of your command (messages, system dialogs, files sent).

## Starting and stopping

```sh
tools/blackbox/bb.sh start          # phone language Arabic (default); takes 1-3 minutes
tools/blackbox/bb.sh start en       # phone language English
tools/blackbox/bb.sh 'tap "مسح"'    # one command; prints the paths and the screen text
tools/blackbox/bb.sh last           # show the latest result again
tools/blackbox/bb.sh stop           # end the session
```

Quote the whole command for the shell, and put labels in double quotes inside it. A `"`
inside a label is written `\"`. Each command waits until the app has finished what it was
doing (at most 30 seconds; use `wait` for longer work). Results are numbered from `000`
(the start screen) and kept in `build/blackbox/session/out/`.

## Reading the screen text

```
Step 004: tap "تصدير"
Result: ok - tapped [10] button "تصدير"
Phone: 1200x2580 px, density 3.0, language ar (right-to-left), text size 1.0x, dark mode off, portrait
Events:
  - announced: "تم الاستيراد: 1"
Screen (what TalkBack can reach; bounds in px [left,top][right,bottom]):
Layer 1: app window (covered)
  [1] button "رجوع"  actions: tap, long-press (إظهار التلميح)  bounds [1044,40][1188,184]
Layer 2: dialog (on top: only this layer can be used)
  [11] radio button "PDF"  {selected}  actions: tap  bounds [599,566][1128,710]
  [12] switch "حماية بكلمة مرور"  {not checked}  actions: tap  bounds [72,2162][1128,2214]
```

* **Result** says whether your command could be carried out (`FAILED` with a reason, for
  example when no element has that label).
* **Events** lists what happened: messages the app announced (`announced: ...`), system
  dialogs that opened (permission, file picker, Save dialog, share sheet), what the
  simulated WhatsApp received, files saved, and `APP STOPPED` if the app crashed. Report
  every `APP STOPPED` with the step number; its details file is for the developers.
* **Layers**: the app window, and on top of it dialogs, bottom sheets and pop-up menus.
  While a dialog or sheet is on top, only that layer can be used (as on a phone).
* Each element: `[number] role "label"`, its state in `{...}` (checked, selected,
  disabled, focused, a state such as "off", progress), what can be done with it
  (`actions`, including the named custom actions TalkBack offers), and where it is.
  An element without a label is one TalkBack cannot name: worth reporting.
* Numbers in Arabic are shown as the app shows them (for example `١` for 1).

## Commands

| Command | What it does |
|---|---|
| `tap "<label>"` | Taps the element with that label. If several match, `tap "<label>" #2` takes the second. `tap @12` taps element `[12]` of the last screen. A label may be given by its first words (`"Done"` for `"Done, pages: 3"`). |
| `longpress "<label>"` | Touch and hold. |
| `type "<field>" "<text>"` | Taps the text field (by its label, hint or current text) and types. |
| `clear "<field>"` | Empties the text field. |
| `scroll down` / `up` / `left` / `right` | Scrolls the largest scrolling area; `scroll down "<label>"` scrolls the area that contains that element. `down` shows what is below. |
| `swipe left` / `right` / `up` / `down` [`"<label>"`] | A quick swipe on the screen or on an element. |
| `drag "<label>" to "<label>"` | Touch and hold the first element, move it onto the second, release. |
| `back` | The phone's Back gesture. On the app's first screen it closes the app; `launch` opens it again. |
| `wait <ms>` | Let time pass (messages disappear after a few seconds, long work finishes). |
| `rotate` | Turns the phone between portrait and landscape. |
| `fontscale <x>` | Phone text size, e.g. `fontscale 1.3` or `fontscale 2.0`. |
| `locale ar` / `locale en` | Phone language. |
| `darkmode on` / `off` | Phone dark theme. |
| `screenshot` | Just look again. |
| `launch` | Opens the app from the launcher after it was closed. |
| `quit` | Ends the session (same as `bb.sh stop`). |
| `help` | The command list. |

### The world around the phone

The simulated phone has no camera hardware, no photo gallery and no other apps, so these
commands stand in for them.

| Command | What it does |
|---|---|
| `pages` | Lists the sheets of paper you can point the camera at (letters in Arabic and English, a bilingual invoice with a table, a form with a reference number to find, a receipt, an ID card, an open book showing two facing pages, an empty desk). |
| `camera_sees <page-id>` | Puts that sheet in front of the camera, as a photo of paper lying on a desk. The next press of the shutter takes it; with automatic capture on, holding it there takes it by itself once. `camera_sees nothing` shows only the desk. |
| `gallery_has <file> [<page-id>\|garbage\|empty\|truncated\|huge]` | Puts a file in the phone's gallery / Files app: a photo of that page (default: the memo), or a damaged file. The extension decides the format (`.jpg`, `.png`, `.webp`, `.heic`). HEIC files are simulated (they hold JPEG data). |
| `pdf_file <name.pdf> <pages> [protected\|broken]` | Puts a PDF with that many pages in the Files app; `protected` has the password `1234`, `broken` is damaged. |
| `files` | Lists the files in the gallery / Files app and in Downloads. |
| `share_in <file> [more files]` | Another app (for example a chat app) uses "Share" to send these files to Scan Kiwi. |
| `pick "<file>" [more]` / `pick_cancel` | Answers the system file picker when the app opens one (the event lists the files it offers). |
| `pick_result ok [name]` / `pick_result cancel` | Answers the system Save dialog yourself. By default Save dialogs are answered automatically (saved to Downloads under the suggested name); `pick_result auto off` turns that off, `pick_result auto on` back on. The event `file saved:` gives the saved file's size (and page count for a PDF). |
| `permission allow` / `permission deny` | Answers a system permission dialog (for example the camera). |
| `whatsapp_target` | WhatsApp is chosen in the share sheet (now, if a share sheet is open, and in later ones). It reports what it received: file name, size, type, for a PDF the page count, whether it has searchable text, and a picture of its first page (`NNN-received-1.png`). `whatsapp_target off` stops choosing it. |

## Known differences from a real phone

* The camera viewfinder is black; what the camera sees is set with `camera_sees`, and the
  shutter, automatic capture and the camera's status line work as on a phone. This stays so
  after `launch`, `locale`, `darkmode` and `fontscale`. If the camera ever says it cannot be
  used, report it.
* Time: the command that opens the camera counts as about 1.5 s of looking at the screen; any
  later command with a sheet in view counts as holding the phone still for up to 4 s. Automatic
  capture waits 2.5 s after the camera opens or the mode changes, so there is time to choose
  «كتاب» / "Book" first.
* There is no internet and no browser: a link the app opens is reported as an event only, and the
  app's own update check («تحقق من التحديثات») always finds no connection.
* Text recognition (OCR) is simulated: it reads the sheets listed by `pages` (each half of the
  open book too) exactly, wherever the app reads text (after a page is added, when exporting,
  in search). A page that is not one of these sheets, such as the empty desk or a blank page,
  has no text. So you can test everything the app does with recognised text, but not how well
  it recognises real paper.
* The app reads a new page's text in the background: give it a moment (`wait 3000`) before you
  look for that text.
* Text fields in dialogs and sheets take `type` as on a phone; the keyboard itself is not shown.
* Animations finish quickly; time only passes while a command runs or with `wait`.

## What to report

For each problem: the steps (the commands you sent, with step numbers), what you expected,
what you saw (screenshot names), and how bad it is for a user:
**S1** data loss, crash or a task that cannot be done; **S2** a main task is hard or wrong
(wrong result, unlabelled control, text cut off, unreadable in a language or text size);
**S3** a smaller flaw with a workaround; **S4** cosmetic.
