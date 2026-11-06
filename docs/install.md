# Install Folio

Folio is in early development. Android preview APKs are available on
[GitHub Releases](https://github.com/Kumar-Aditya3/Folio/releases/tag/v1.2.131).
Windows currently builds from source. There are no official Play Store or F-Droid listings.

## Android

Requires Android 7.0 or later. Download one APK from the release's **Assets** section:

| File ending | Choose it for |
| --- | --- |
| `arm64-v8a.apk` | Most current Android phones and tablets. |
| `armeabi-v7a.apk` | Older devices running 32-bit ARM Android. |
| `universal.apk` | Either ARM architecture, if you are unsure; the download is larger. |

Open the APK on your device and follow Android's installation prompts. Android may ask you
to allow installation from the browser or file manager you used. Only enable that permission
for the source you intend to use. These release APKs do not support x86 Android emulators.

For updates, install a newer APK signed by the same Folio release key over the existing app.
Keep a library backup before updating. If Android reports a signing conflict with a locally
built version, export your data before changing installations; uninstalling can erase it.

The release includes `SHA256SUMS.txt` for checking file integrity. On Windows:

```powershell
Get-FileHash .\Folio-1.2.131-arm64-v8a.apk -Algorithm SHA256
```

On Linux use `sha256sum`; on macOS use `shasum -a 256`. Compare the result to the matching
filename in `SHA256SUMS.txt`. The signing certificate fingerprint is also recorded in the
release notes. Checksums verify the download, rather than proving that software is safe.

## First read

1. Open Folio and import a DRM-free EPUB, document, or local manga archive you own or may use.
2. Open the title from your library, then adjust the type and theme in the reader settings.
3. Save a passage with a highlight or bookmark. Folio keeps your reading position locally.

Offline reading does not require an account or Firebase setup. Narration and semantic search
use optional downloads; download the required voice pack or model before going offline.

## Windows

Windows 10 x64 or later is the supported desktop target. Follow the
[build instructions](../README.md#getting-started) with JDK 17. You can run the app directly
or build an MSI, EXE installer, or portable application folder. A portable build includes
its Java runtime; keep the entire generated `Folio` folder together.

Linux, macOS, and iOS are not currently supported runtime targets.

## Questions readers ask

**Does Folio provide books or manga?** No. Import your own files. Android supports compatible
Mihon/Tachiyomi source extensions, which are separate third-party software. Folio does not
host or distribute books, manga, or source extensions. Use sources you have permission to access.

**Can it open DRM-protected purchases?** Folio is intended for DRM-free files and does not
remove DRM. Importing PDF, DOCX, or ODT does not promise identical layout to the original app.

**Does everything stay offline?** Your imported library and reading activity are local.
Online sources, extension checks, optional model and voice downloads, and cloud sync use
the network. Optional sync requires your own Firebase configuration. See the
[configuration section](../README.md#configuration).

**Do Android and Windows have the same features?** They share much of the reader UI, but
Android is the primary target. Source extensions, offline narration, home-screen widgets,
and reminders are Android features. Windows uses a JCEF-based reader surface.

**How do I report a problem?** [Open an issue](https://github.com/Kumar-Aditya3/Folio/issues/new/choose)
with your Folio version, platform, and steps to reproduce. Describe the file format; share
a small public-domain or self-authored sample if needed. Avoid uploading personal library
databases, account settings, or copyrighted books.
