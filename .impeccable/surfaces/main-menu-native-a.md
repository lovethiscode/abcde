# Native main menu · selected A
Mode: Operate. Scope: iOS CampaignHomeMenu only, existing navigation and platform gates.
User selected A and confirmed expedition blue #17343F; now requests native implementation and page localization.
Reference: ../../../docs/design/main-menu-prototypes-20261016/a-refined/ (private parent checkout).
Preserve the current gate icon, localized slogan, BitmapFont and existing icons; do not restore the removed upstream wordmark.
Campaign map cover leads, gold resume/new-game strip follows, four gameplay entries form a 2×2 group, tools form one bottom row. Wide landscape places campaign left and actions right; portrait/narrow windows stack. Natural vertical scrolling accommodates short windows and long translations. Never shrink translated text or truncate primary labels. Minimum 44 UIKit-point targets via UiNavigation scaling.
Reuse supplied Steam map art, blue readability scrim, warm gold and theme text tokens. No new gameplay or remote integrations. No-save offers new game. All visible functional copy uses translation keys; all supported locales must have values. Verify real iPhone/iPad simulator renders, long translations, font scaling, navigation and autosave preservation. Browser prototypes are visual references only.
Localization boundary: 46 supported languages have localized home copy. Navajo retains the project's English fallback because no reviewed Navajo home translations are available; schema and layout checks still include it. Do not count nonempty English placeholders as translated copy.

Large game-font adaptation: below 600pt action-panel width and font multiplier above 1.25, gameplay entries use one column; below 900pt tools use two columns. All rows derive height from content, with minimums only. Validate label bounds against their owning button, including German at 1.5 on 375pt.

## Direction contract

THESIS: Implement the user's selected A campaign-first home in the native game, with the existing destinations and a clear return-to-game action.

OWN-WORLD: Preserve expedition blue #17343F, warm gold actions, the gate icon, existing game icons and BitmapFont. Reuse the approved map artwork under a blue readability scrim.

STORY: Continue the latest save or start a new game; find campaigns, free play, endless, load, multiplayer, tools and settings through their existing callbacks. Localize visible copy and let long text grow.

FIRST VIEWPORT: Portrait stacks campaign cover, resume/new-game strip, gameplay grid and tools. Wide iPad places cover left and actions right. Short windows scroll; large text reflows to fewer columns rather than clipping.

FORM: User-pinned A adaptation, inherited from the private parent's `.impeccable/surfaces/main-menu-a-refined-20261016.md`. No new concept roll or catalog QUALITY BAR card was used for this native extension. The approved A prototype is the visual reference; original candidate review records the earlier unavailable seed. This is a disclosed process boundary, not a newly corroborated seed reference.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

2026-10-16 user correction: normal-size iPhone home should show all entries in the first viewport on the current 440x860 portrait and 832x420 landscape safe areas. A wide phone with height below 600pt uses a compact landscape variant, not the iPad minimums. Keep normal 17pt entry/12pt supporting text, reduce display title to28pt and resume to20pt, 12pt margins/8pt gaps, 64pt resume and80pt gameplay row minima, inline44pt tools. Resolve cover wrapping before measuring its preferred height. Larger fonts and longer translations may scroll without clipping.
