# Reader transparency across content hosts

Release: 1.2.106 (160).

EPUB and DOCX readers do not publish a captured Compose page. Their controls still selected optical glass, whose opaque fallback could cover the page; without any source its optical pass could also omit the material fill, making intermediate slider values appear unchanged. These WebView readers now explicitly use the translucent painted material. PDF keeps its captured page blur and Home keeps its existing optical treatment.

Hosted menus and dialogs are drawn outside the reader subtree. They now carry the caller's backdrop policy into the overlay host and retain it for nested menus, so the WebView fallback does not get lost when opening a popup. Open panes can update this policy without being recreated. Their retained render frames observe opacity and backdrop policy as snapshot state, so Compose cannot skip a live material update simply because the frame object has the same identity.

Manga used independently painted black gradients with fixed opacity. Its top and bottom bars now read the in-reader setting, including the diffusion fallback, and become solid at 100%. Manga dialogs and the document font menu use the same reader panel setting. Manga note rows no longer add an opaque recessed fill inside the reader dialog. Manga bar highlights also follow metallic sheen.

`ReaderHostTransparencyTest` renders the actual reader capsule and manga controls over red and blue pages. It covers missing sources, an unrelated captured background, and a captured page, with blur enabled and disabled. A third check renders a real hosted reader dialog over an unrelated captured background and verifies that its fallback and token survive live changes. The checks require visible transmission at 20%, reduced transmission at 60%, no transmission at 100%, and live restoration to 20%. Native images are saved in `shared/build/visual-review/reader-hosts/` and copied into `outputs/` during release verification.

Release verification and installation status are recorded separately in `outputs/reader-opacity-hosts-*.json`. These native rendering checks do not replace an on-phone reader check.

Verification completed: 61 targeted tests across 14 suites passed, and the signed arm64, armv7 and universal release APKs were verified. Version 1.2.106 (160) was installed through ADB on the connected Oppo CPH2717; the installed package version was checked. The app was left closed, following the user's install-only preference.
