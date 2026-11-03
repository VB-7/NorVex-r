# NorVex-R

Aplikasi Android untuk generator Perchance **norvex-r** (obrolan karakter AI).
Web dibungkus jadi aplikasi mandiri: bersih dari menu/iklan Perchance, bisa
unduh file, backup otomatis, dan update dari dalam aplikasi.

## Unduh & pasang

Ambil APK terbaru di halaman **[Releases](../../releases)**.
Pasang di HP, buka, langsung pakai. Update berikutnya ditawarkan sendiri
oleh aplikasi (tiap buka dicek; tinggal ketuk "Pasang" sekali).

## Yang ada di aplikasi

- **Tampilan bersih** — hanya isi generator; menu, konsol editor, dan kotak
  iklan Perchance disembunyikan. Bar sistem tampil normal (tidak fullscreen).
- **Unduh** — file dari web tersimpan ke `perchance_app/Roleplay/download/`
  (gambar ke `download/image/<nama-kartu>/`). File besar dikirim
  potongan-potongan lalu ditulis langsung, tanpa upload dulu.
- **Pratinjau gambar** — lihat dulu sebelum unduh (zoom + geser untuk tutup).
- **Backup otomatis** — tiap data berubah → folder tersembunyi `.backup/`
  (selamat dari hapus data browser / storage mepet). Diam-diam, tanpa notif.
- **Galat tercatat** — error aplikasi & web masuk `error/` (trace/crash log).
- **Tombol kembali bertingkat** — tutup pop-up web dulu dari yang terbaru;
  keluar aplikasi selalu pakai konfirmasi.
- **Tautan luar** — link ke halaman perchance lain dibuka di browser,
  tidak di dalam aplikasi (riwayat webview tetap bersih).
- **Kunci potret** — sensor/konten landscape tidak memutar aplikasi.
- **Webview tanpa state** — tiap buka selalu halaman generator yang segar.

## Folder di HP (`perchance_app/Roleplay/`)

- `download/` — hasil unduhan (disembunyikan dari galeri).
- `.backup/` — cadangan otomatis (folder tersembunyi).
- `error/` — `trace-*.txt`, `log-*.txt`, `crash-*.txt` untuk diagnosis.

## Bangun sendiri (cloud, tanpa Android Studio)

1. Fork repo ini.
2. Push sekali → workflow **Build APK** jalan. Run pertama membuat kunci
   baru: ambil `kunci-tanda-tangan.txt` dari artifact → simpan isinya ke
   secret `KEYSTORE_B64` → jalankan ulang workflow (tombol Re-run).
   Mulai run kedua, APK terbit otomatis di **Releases** (`NorVex-R b<nomor>`).

## Struktur kode

- `app/src/main/java/com/norvex/roleplay/MainActivity.java` — seluruh aplikasi
  (webview, unduh, pratinjau, backup, back, update, kosmetik).
- `.../TaskService.java` — service latar + notifikasi progres.
- `.../MiniFileProvider.java` — pasang APK hasil unduhan (tanpa library).
- `app/src/main/AndroidManifest.xml` — izin, kunci potret, adjustResize.
- `.github/workflows/build-apk.yml` — rakit + rilis otomatis.

## Aturan main (jangan dilanggar)

- **Jangan sembunyikan** `#editorEl`, `#main`, `#b`, `#e`, `#output`,
  `#outputIframeEl` — itu induk/sarang generator; disembunyikan = layar hitam.
  Fakta ini ditemukan lewat bedah HTML halaman publik (b45/b47 hitam
  gara-gara `#editorEl` ikut disembunyikan).
- Yang boleh disembunyikan: 6 id iklan
  (`minimalModeMenuBtn`, `backToMinimalModeBtn`, `adCtn`,
  `adsSandboxIframeEl`, `adShieldEl`, `whyAdsButton1El`) + `#menuBarEl`
  (saudara aman di luar output), plus gelapkan `html,body`.
