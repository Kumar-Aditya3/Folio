# Contributing to Folio

Thanks for helping make Folio a better place to read. It is an independent project in early
development, so small, focused contributions are easiest to review.

## Useful ways to help

- Try a [preview download](https://github.com/Kumar-Aditya3/Folio/releases)
  with a DRM-free book you can share and report a reproducible import or reader problem.
- Test keyboard navigation, large text, contrast, and screen-reader behavior.
- Improve installation instructions or clarify platform differences.
- Fix a specific bug with a regression test when it protects meaningful behavior.
- Share the project with readers who use their own ebook files.

Use the [issue templates](https://github.com/Kumar-Aditya3/Folio/issues/new/choose) for bug
reports and feature ideas. For a large change, explain the problem in an issue before
investing in an implementation. Responses may take time.

## Development

The [README](README.md#getting-started) covers prerequisites, the project layout, configuration,
and build commands. JDK 17 is the documented baseline. Android builds also need SDK 37.
Never commit local credentials, signing material, personal library data, or downloaded models.

```bash
./gradlew :shared:desktopTest
./gradlew :desktopApp:compileKotlin
./gradlew :androidApp:assembleDebug
```

Use `gradlew.bat` on Windows. The desktop runtime currently supports Windows x64 only;
JVM compilation and many shared tests can run elsewhere. Android instrumented tests mutate
the application's database: use a test device or emulator with expendable data.

Keep a pull request focused, explain the user-visible behavior, and state which checks you
ran. UI changes benefit from before/after images using fictional or public-domain demo data.
Preserve license notices for vendored code and review [NOTICE](NOTICE) when changing it.

Contributions are made under the project's [Apache 2.0 license](LICENSE). Be considerate
and discuss the work, not the person.
