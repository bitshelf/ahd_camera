# AHD Camera development

Before Android work, inspect the official Android skill catalog in `skills/README.md`
and the installed skills under `.agents/skills/`. Read the relevant `SKILL.md` and
its required references before implementing or validating that workflow. State
which skill is being used. Apply only skills relevant to the task; if none matches,
use the official Android documentation and say so. Do not claim a skill was used
merely because it is installed.

The upstream checkout is `skills/` (https://github.com/android/skills, branch main).
Each `.agents/skills/<name>` links to the corresponding upstream skill directory.
Both directories are local tooling and excluded from Git. For a fresh checkout,
clone the upstream repository into `skills/`, then link every directory containing
`SKILL.md` into `.agents/skills/` using the skill directory name. Verify every link
resolves. Update with `git -C skills pull --ff-only`, then add links for new skills.

Keep user-facing text in Android resources. `res/values/strings.xml` is the complete
English fallback; `res/values-zh/strings.xml` contains Chinese translations. Use
`stringResource`/`pluralStringResource` in Compose and resources from a Context
outside composables. Use indexed format placeholders and plurals for quantities.
Never embed Chinese UI messages in Kotlin, Java, or Rust. Technical device names,
protocol values and diagnostic details may remain untranslated.

This branch implements UI, Camera2 capture and MediaCodec recording in Kotlin.
The App must not introduce Rust, C++, JNI or direct device access. The optional
independent system Provider under provider/ is authorized; keep its capture core,
versioned AIDL adapter and board integration separate. Never modify existing
USB/MIPI HAL or framework source. Use public Camera2 capabilities in the App; never
invent concurrency, resolution or FPS support. Keep Soong and Gradle compatible.
Do not overwrite another machine's source or its uncommitted resources.

UI changes must keep `docs/AHD_Camera_Interactive_Prototype.html` and the native
Compose presentation synchronized in the same change. Follow `docs/ui-prototype.md`
for the shared interaction contract and justified native differences. HTML images,
camera IDs and mode lists are demo fixtures, never discovery data for the App.
Keep one FPS selector and one measured preview badge; never fake the measurement
to match a selected value. Verify physical layout on the authorized DUT when it is
available, and explicitly distinguish build checks from actual device acceptance.
