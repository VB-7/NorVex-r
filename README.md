# NorVex-R

Aplikasi Android native (Java) pembungkus generator web `norvex-r` (perchance.org).
WebView penuh + jembatan `window.AppInventor` (kompatibel protokol MIT).

## Fitur

- Fullscreen imersif + ikon + tema gelap.
- Folder wajib: `perchance_app/Roleplay/{download/,download/image/<kartu>,error/,.backup/}` (+ `.nomedia` anti-galeri).
- Unduhan mentah per potong (termasuk blob) tanpa upload; http via DownloadManager.
- Backup lokal otomatis ke `.backup/`; checkpoint saat pause.
- Dialog error + log file (`error/`); trace + crash logger untuk buru force-close.
- Back bertingkat (dialog/web/konfirmasi keluar); pratinjau gambar dialog WebView (zoom, swipe tutup).
- Foreground service + notifikasi selama ada tugas (unduhan / AI menjawab).
- Update dalam aplikasi via GitHub Release.
- AdBlock dua level ala Sunny Gupta: jaringan (`shouldInterceptRequest` + `AD_HOSTS`)
  dan cosmetic (`COSMETIC_CSS`, rules `domain###id`).

## Struktur

- `app/src/main/java/com/norvex/roleplay/MainActivity.java` — hampir semua logika.
- `app/src/main/java/com/norvex/roleplay/TaskService.java` — foreground service.
- `app/src/main/java/com/norvex/roleplay/MiniFileProvider.java` — install APK update.
- `app/src/main/AndroidManifest.xml`, `app/build.gradle`, res/.
- `.github/workflows/build-apk.yml` — build otomatis.

## Tambah rules AdBlock

- Host iklan: tambah baris domain di `AD_HOSTS` (MainActivity.java).
- Elemen halaman (`domain###id`): tambah selector di `COSMETIC_CSS`.
- Commit ke `main` lalu build otomatis, APK di Releases.

## Build

Push ke `main` memicu GitHub Actions (JDK 17, AGP 8.5.2). Kunci tanda-tangan dari
secret `KEYSTORE_B64` (stabil antar build); tanpanya tiap build memakai kunci baru.
