# Native main menu · selected A
Mode: Operate. Scope: iOS CampaignHomeMenu only, existing navigation and platform gates.
User selected A and confirmed expedition blue #17343F; now requests native implementation and page localization.
Reference: ../../../docs/design/main-menu-prototypes-20261016/a-refined/ (private parent checkout).
Preserve the current gate icon, localized slogan, BitmapFont and existing icons; do not restore the removed upstream wordmark.
Campaign map cover leads, gold resume/new-game strip follows, four gameplay entries form a 2×2 group, tools form one bottom row. Wide landscape places campaign left and actions right; portrait/narrow windows stack. Natural vertical scrolling accommodates short windows and long translations. Never shrink translated text or truncate primary labels. Minimum 44 UIKit-point targets via UiNavigation scaling.
Reuse supplied Steam map art, blue readability scrim, warm gold and theme text tokens. No new gameplay or remote integrations. No-save offers new game. All visible functional copy uses translation keys; all supported locales must have values. Verify real iPhone/iPad simulator renders, long translations, font scaling, navigation and autosave preservation. Browser prototypes are visual references only.
Localization boundary: 46 supported languages have localized home copy. Navajo retains the project's English fallback because no reviewed Navajo home translations are available; schema and layout checks still include it. Do not count nonempty English placeholders as translated copy.

Large game-font adaptation: below 600pt action-panel width and font multiplier above 1.25, gameplay entries use one column; below 900pt tools use two columns. All rows derive height from content, with minimums only. Validate label bounds against their owning button, including German at 1.5 on 375pt.
