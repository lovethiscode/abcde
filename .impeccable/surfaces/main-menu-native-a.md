# Native main menu · selected A
Mode: Operate. Scope: iOS CampaignHomeMenu only, existing navigation and platform gates.
User selected A and confirmed expedition blue #17343F; now requests native implementation and page localization.
Reference: ../../../docs/design/main-menu-prototypes-20261016/a-refined/ (private parent checkout).
Preserve the current gate icon, localized slogan, BitmapFont and existing icons; do not restore the removed upstream wordmark.
Campaign map cover leads, gold resume/new-game strip follows, four gameplay entries form a 2×2 group, tools form one bottom row. Wide landscape places campaign left and actions right; portrait/narrow windows stack. The home screen has no scroll container. Reserve safe-area space for every entrance; reduce artwork height first, then auxiliary copy and decorative icons through measured compact densities. Never truncate primary labels. Minimum 44 UIKit-point targets via UiNavigation scaling.
Reuse supplied Steam map art, blue readability scrim, warm gold and theme text tokens. No new gameplay or remote integrations. No-save offers new game. All visible functional copy uses translation keys; all supported locales must have values. Verify real iPhone/iPad simulator renders, long translations, font scaling, navigation and autosave preservation. Browser prototypes are visual references only.
Localization boundary: 46 supported languages have localized home copy. Navajo retains the project's English fallback because no reviewed Navajo home translations are available; schema and layout checks still include it. Do not count nonempty English placeholders as translated copy.

Large game-font adaptation: measure preferred content height at the current safe viewport and game font multiplier. If regular content does not fit, keep the gameplay group in two columns, omit secondary descriptions, and finally retain only functional labels in the tightest density. Tools use two columns below 520pt at font multiplier above 1.25. All rows derive height from content, with 44pt target minimums. Validate label bounds against their owning button, including German at 1.5 on 375pt.

## Direction contract

THESIS: Implement the user's selected A campaign-first home in the native game, with the existing destinations and a clear return-to-game action.

OWN-WORLD: Preserve expedition blue #17343F, warm gold actions, the gate icon, existing game icons and BitmapFont. Reuse the approved map artwork under a blue readability scrim.

STORY: Continue the latest save or start a new game; find campaigns, free play, endless, load, multiplayer, tools and settings through their existing callbacks. Localize visible copy and let long text grow.

FIRST VIEWPORT: Portrait stacks campaign cover, resume/new-game strip, gameplay grid and tools. Wide iPad places cover left and actions right. Short windows and large text select a compact composition that keeps every entrance inside the first viewport.

FORM: User-pinned A adaptation, inherited from the private parent's `.impeccable/surfaces/main-menu-a-refined-20261016.md`. No new concept roll or catalog QUALITY BAR card was used for this native extension. The approved A prototype is the visual reference; original candidate review records the earlier unavailable seed. This is a disclosed process boundary, not a newly corroborated seed reference.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

2026-10-16 user correction: normal-size iPhone home should show all entries in the first viewport on the current 440x860 portrait and 832x420 landscape safe areas. A wide phone with height below 600pt uses a compact landscape variant, not the iPad minimums. Keep normal 17pt entry/12pt supporting text, reduce display title to28pt and resume to20pt, 12pt margins/8pt gaps, 64pt resume and80pt gameplay row minima, inline44pt tools. Resolve cover wrapping before measuring its preferred height. Superseded by the user correction below: no home scrolling at any supported size or game font setting.

2026-10-16 free-play extension: refine the modal reached from Free play within pinned A. Preserve Quickstart and Singleplayer destinations, current translated titles/descriptions, back/close/outside dismissal and existing game lettering. Use a bounded expedition-blue panel, compact icon/copy/arrow actions, warm gold for customized Singleplayer, two columns when readable width allows and stacked actions on narrow windows or enlarged game fonts. Content must grow and scroll without truncation; retain a fixed native close control. Validate ordinary native iPhone/iPad captures, both destinations on an isolated simulator, translations and original save preservation. Acceptance is recorded in the private parent's native-implementation/free-play directory.

2026-10-16 viewport correction: iPhone and iPad home must fit both orientations, including small iPhone safe areas and the supported 1.5 game-font maximum. Remove the home ScrollPane and choose normal, compact or minimal density using actual wrapped preferred height. Preserve all functional entrances, existing callbacks, expedition blue, artwork and minimum 44pt controls. Free-play popup scrolling remains outside this home-only correction. Native evidence and the final acceptance boundary are recorded in the private parent’s native-implementation/viewport-fit directory.


2026-10-17 approved replacement composition: implement private parent docs/design/main-menu-prototype-20261017. Phone portrait puts resume before the short campaign cover; phone landscape splits cover/actions; iPad both orientations use two columns. Separate Free play/Endless from outlined Load/Multiplayer. Campaign action is outlined; only Resume/New game is solid gold. Retain callbacks, translation and BitmapFont. User explicitly requires all actions in the first viewport at every supported size and game font setting, with no home scrolling or scrollbar: measured compact/minimal densities shrink artwork, auxiliary copy and decorative icons before functional labels; keep 44pt targets. This supersedes the browser prototype large-text scrolling behavior. Validate the final ordinary build on iPhone/iPad both orientations and enlarged game fonts; preserve original saves.
