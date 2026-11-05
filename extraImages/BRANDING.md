# App branding assets

The canonical full-color artwork is
`ios/app-store-assets/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png`.
It is an opaque 1024 × 1024 square without a baked-in platform mask. Replace that
file when changing the approved app artwork, then regenerate the platform copies
from the public repository on macOS:

```sh
swift extraImages/generate-brand-assets.swift
swift ios/app-store-assets/generate-launch-artwork.swift
```

The first command exports Android launcher layers, legacy and round icons,
store icons, shared/desktop icons, ICO files, documentation icons, and the
current startup/store/social banners. Wide banners contain the square artwork
without stretching it. Transparent Steam logo canvases retain their layout.
Legacy filenames remain for compatibility; their names do not select older art.

The second command exports the square 3x startup marks from the same source.
UIKit's launch storyboard, the shared iOS initialization screen and the home
header read `LaunchMarkTransparent@3x.png`. Its alpha is limited to the UI mark's
rounded corners; the approved painting itself remains intact. The existing
startup background and layout are retained.

Android adaptive foregrounds use a 108dp layer and a centered 66dp content
region, paired with `uncivicon_background`. System launcher masks are applied at
runtime. See [Android's adaptive icon guidance](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).

Game maps, unit/terrain illustrations, functional UI glyphs, monochrome
notification status symbols, tutorial screenshots and historical release/design
archives are separate content, not app-brand artwork. Regeneration does not
overwrite them. These commands generate resources only; they do not build,
install, run tests, publish, or upload any application.
