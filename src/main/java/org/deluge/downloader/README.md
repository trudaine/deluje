# Deluge Patch & Sample Downloader

This directory contains a collection of patches and sample packs for the Synthstrom Deluge, crawled from the official forums.

## Repository Contents

- **Subdirectories**: Each folder is named after a forum discussion and contains the retrieved `.XML` patches and `.WAV` samples.
- **`download_log.md`**: A comprehensive index of all processed forum discussions, their status, and the files downloaded.
- **`external_links.txt`**: A list of external resources (Google Drive Folders, Mega.nz) that require manual downloading.

## Tools

### 1. Python Downloader (`deluge_downloader.py`)
A lightweight script for crawling the forums.
- **Prerequisites**: `pip install requests`
- **Usage**: `python3 deluge_downloader.py`

### 2. Java Downloader (`DelugeDownloader.java`)
A feature-complete equivalent that requires no external libraries.
- **Prerequisites**: Java 11 or newer.
- **Compilation**: `javac DelugeDownloader.java`
- **Usage**: `java DelugeDownloader`

Both scripts handle:
- Forum attachments (XML, ZIP, RAR, 7Z).
- Public Dropbox direct links.
- Public Google Drive individual file links.

### Extraction Utility
To extract all downloaded archives into their respective folders, you can use:
```bash
find . -name "*.zip" -exec unzip -o {} -d $(dirname {}) \;
find . -name "*.rar" -exec unrar x -o+ {} $(dirname {}) \;
find . -name "*.7z" -exec 7z x -aoa {} -o$(dirname {}) \;
```

## Forum Sources
- [Deluge Sample Packs](https://forums.synthstrom.com/categories/deluge-sample-packs)
- [Deluge Patches](https://forums.synthstrom.com/categories/deluge-patches)
