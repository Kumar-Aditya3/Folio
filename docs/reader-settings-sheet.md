# Reader settings sheet

Release: 1.2.107 (161).

The right-hand reader settings drawer used the older beveled veil and always discounted its fill as though a blurred page were behind it. EPUB and DOCX use WebViews without a captured Compose backdrop. After fixing their opacity paths, that discount exposed sharp page text through the settings controls.

Reader panel opacity now uses the setting verbatim when blur is unavailable. The normal 90% preference gives an unblurred drawer 90% body, rather than roughly 62%. Captured PDF/manga backdrops and embedded HTML overlays retain their diffusion mapping. Low values still respond, 100% is solid, and the app panel preference remains independent.

Book and document settings drawers share the main Settings reference material: neutral palette-relative fill, one thin directional edge, and diffuse backdrop treatment where supported. Their docked shape and entrance motion are retained. Typeface selectors, text-size controls and the All settings action use the same quiet embedded-choice treatment as Settings. A reader sheet remains an overlay when minimal in-flow glass is selected.

`ReaderSettingsSheetTest` checks the opacity mapping and actual book/document drawers in light and dark themes, with device effects enabled and disabled over a WebView-style host. It measures normal, low, solid and restored-low transmission and verifies that the app panel knob does not alter the reader sheet. Native previews cover both drawers at 393dp and 320dp with 140% text over a dense page of text.

Build, test, signing and installation evidence is recorded in `outputs/reader-settings-sheet-*`.

Completed: 64 targeted tests across 15 suites passed, all three release APK signatures were verified, and 1.2.107 (161) was installed on the connected Oppo through ADB. The installed package version was verified. The app was left closed; the visual review used native desktop renders rather than phone interaction.
