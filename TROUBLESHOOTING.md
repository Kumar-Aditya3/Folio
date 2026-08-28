# Folio EPUB Reader - Troubleshooting Guide

## Common Issues and Solutions

### 1. Font Display Issues / Garbled Text in Settings

**Symptoms:**
- Font names appear corrupted or with strange characters
- Font preview shows incorrect glyphs
- Settings screen text is unreadable

**Root Cause:**
The font system fails to load system fonts and falls back silently without proper error handling.

**Solution:**
The fix has been implemented with:
- Proper error logging showing which fonts fail to load
- Fallback to generic font families (Serif, Sans-Serif, Monospace, Default)
- Console messages showing font mapping decisions

**To verify the fix:**
1. Check your application logs for messages like:
   - `Font system: Could not find font 'FontName', falling back to generic family`
   - `Font system (Android): Mapped 'FontName' to generic family`
2. If you see these messages, the font system is working but the requested font isn't installed
3. Try selecting different fonts or install custom fonts on your system

---

### 2. Authentication / Sign-in Errors

**Symptoms:**
- "Cloud sync not configured" error when trying to sign in
- Authentication fails with unclear error messages

**Root Cause:**
Firebase credentials are missing or invalid in the `.env` file.

**Solution:**

1. Create or edit the `.env` file in the project root:
```env
projectId=your-firebase-project-id
apiKey=your-firebase-api-key
email=optional-service-account-email
password=optional-service-account-password
storageBucket=optional-storage-bucket-name
```

2. For Android, you can also add these to `gradle.properties` or as string resources:
```xml
<!-- androidApp/src/main/res/values/firebase_config.xml -->
<resources>
    <string name="folio_fb_project_id">your-project-id</string>
    <string name="folio_fb_api_key">your-api-key</string>
</resources>
```

3. To set up Firebase:
   - Go to [Firebase Console](https://console.firebase.google.com/)
   - Create a new project or use an existing one
   - Navigate to Project Settings
   - Copy the Web API Key and Project ID
   - Add them to your `.env` file

**Note:** Cloud sync is optional. The app works perfectly fine without Firebase credentials for local reading.

---

### 3. Android EPUB Import Stuck at "Importing..."

**Symptoms:**
- After selecting an EPUB file, the app shows "Importing..." indefinitely
- No progress indication
- App doesn't crash but import never completes

**Root Causes:**
- Search indexing blocking the main thread (especially with large books)
- No progress logging to identify where the process stalls
- File access permissions not properly handled

**Solution:**

The import process now includes detailed logging at each step:

1. **Check Android Logcat** for import progress messages:
```
Import: Starting import of /path/to/book.epub
Import: Calculating file hash...
Import: Checking for duplicates...
Import: Parsing EPUB...
Import: Parsed 150 chapters, 125000 words
Import: Generated book ID: abc-123
Import: Copying file to library...
Import: Extracting cover...
Import: Creating book record...
Import: Saving to database...
Import: Building search index (150 chapters)...
Import: Indexing 150 chapters in bulk...
Import: Search indexing complete
Import: Saving metadata...
Import: Successfully imported 'Book Title'
```

2. **If the import stalls:**
   - Check which step was last logged
   - Common stall points:
     - **"Calculating file hash"**: File may be corrupted or inaccessible
     - **"Parsing EPUB"**: EPUB file may be malformed
     - **"Building search index"**: This can take time with large books (30+ seconds for 500k+ word books)
     - **"Indexing chapters in bulk"**: Database write operation

3. **Permissions on Android:**
   - Ensure the app has READ_EXTERNAL_STORAGE permission
   - For Android 13+, use READ_MEDIA_IMAGES and READ_MEDIA_VIDEO
   - Grant permissions through Settings → Apps → Folio → Permissions

4. **For large books:**
   - Be patient - a 500-page book can take 30-60 seconds to import on slower devices
   - The bulk indexing operation is now more efficient but still takes time

---

### 4. Build Artifacts and Repository Clutter

**Symptoms:**
- 50+ `build_*.txt` files in the repository
- Large `.epub` test files committed
- `thorium-reader-develop` directory included

**Solution:**

These have been added to `.gitignore`:
- `build_*.txt` - All build log files
- `testbooks/` - Test EPUB files directory
- `*.epub` - Individual EPUB files
- `thorium-reader-develop/` - Separate Thorium Reader project

**To clean up existing files:**

```bash
# Remove build logs
rm build_*.txt

# Remove test books (optional - you may want to keep them locally)
# They're now gitignored, so won't be committed even if present
rm -rf testbooks/

# Remove Thorium Reader (if it's a separate project)
rm -rf thorium-reader-develop/

# Commit the cleanup
git add .gitignore
git commit -m "Clean up repository and update gitignore"
```

---

## Performance Optimization Tips

### Import Performance
- **Expect**: 1-5 seconds for typical novels (60k-150k words)
- **Large books**: 20-60 seconds for 300k+ words books
- The search indexing is the most time-consuming step

### Font Loading
- System font lookup happens once per app launch
- Fallback fonts load instantly
- To improve performance, use generic font families (Serif, Sans-Serif, Monospace)

### Cloud Sync
- Sync is optional and doesn't affect local reading performance
- When enabled, sync happens in the background
- Large libraries may take time on first sync

---

## Debugging Tips

### Enable Verbose Logging

The app now includes verbose logging for:
- Font system operations
- Authentication attempts
- Import progress
- Error conditions

**To view logs:**

**Android:**
```bash
adb logcat | grep -E "Font system|Import:|Authentication"
```

**Desktop:**
Check the console output where you launched the application.

### Common Error Messages

| Error Message | Meaning | Solution |
|--------------|---------|----------|
| "Font system: Could not find font" | System font not installed | Use a generic family or install the font |
| "Cloud sync not configured" | Firebase credentials missing | Add credentials to `.env` or disable sync |
| "Import: FAILED" | Import error with stack trace | Check the specific error in logs |
| "Duplicate detected" | Book already exists | This is intentional duplicate prevention |

---

## Getting Help

If you're still experiencing issues:

1. **Check the logs** - Most issues show clear error messages now
2. **Verify prerequisites** - Ensure all dependencies are installed
3. **Try a clean build** - Delete `build/` directories and rebuild
4. **Test with a simple EPUB** - Use a small, known-good EPUB file to isolate the issue

For bug reports, include:
- Platform (Android/Desktop)
- Error messages from logs
- Steps to reproduce
- EPUB file details (if import-related)