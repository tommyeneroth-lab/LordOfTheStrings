# OMR Server for Lord of the Strings

A Python server that performs Optical Music Recognition on PDF and image files using [oemer](https://github.com/BreezeWhite/oemer). The Android app sends files to this server and receives MusicXML back.

## API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/omr` | POST | Submit a file for OMR processing |
| `/health` | GET | Health check — returns `{"status": "ok"}` |

### POST /omr

**Request body** (JSON):
```json
{
  "pdf_base64": "<base64-encoded PDF or image>",
  "filename": "score.pdf"
}
```

**Response** (JSON):
```json
{
  "musicxml": "<?xml version=\"1.0\" ...?><score-partwise>...</score-partwise>"
}
```

The server auto-detects the file type by extension. PDFs are split into pages and each page is processed individually, then merged into a single MusicXML document.

## Setup

### Option 1: Local Python

**Prerequisites:**
- Python 3.10+
- [Poppler](https://poppler.freedesktop.org/) (for PDF page rendering)
  - **Ubuntu/Debian:** `sudo apt install poppler-utils`
  - **macOS:** `brew install poppler`
  - **Windows:** Download from [poppler releases](https://github.com/ossamamehmood/Poppler-windows/releases) and add to PATH

**Install and run:**
```bash
cd server
pip install -r requirements.txt
python omr_server.py
```

The server starts on `http://0.0.0.0:5000` by default.

**Options:**
```
--host 127.0.0.1    # Bind to localhost only
--port 8080         # Custom port
--debug             # Enable Flask debug mode
```

### Option 2: Docker

```bash
cd server
docker build -t lots-omr .
docker run -p 5000:5000 lots-omr
```

### Option 3: Docker Compose

Create a `docker-compose.yml`:
```yaml
services:
  omr:
    build: .
    ports:
      - "5000:5000"
    restart: unless-stopped
```

Then: `docker compose up -d`

## Connecting the App

1. Open the app and go to **Settings**
2. Enter the server URL in the **OMR Server URL** field, e.g.:
   - Same machine: `http://10.0.2.2:5000` (Android emulator) or `http://<your-ip>:5000` (physical device)
   - Remote server: `https://your-server.example.com`
3. The app will show a green indicator when the server is reachable
4. When importing a **PDF** or **JPEG/photo**, the app automatically uses the server. If the server is unreachable or fails, it falls back to on-device OMR.

## Performance Notes

- oemer processing takes **30 seconds to 3 minutes** per page depending on complexity and hardware
- A GPU is not required but significantly speeds up processing
- The server timeout is set to 5 minutes to accommodate slow processing
- For best results, use clean printed scores at 300 DPI or higher

## Troubleshooting

| Problem | Solution |
|---------|----------|
| App shows "Server failed — falling back to on-device OMR" | Check that the server is running and the URL is correct. Test with `curl http://<server>:5000/health` |
| `pdf2image` import error | Install poppler: `apt install poppler-utils` (Linux) or `brew install poppler` (macOS) |
| oemer produces no output | Ensure the input image is a clean scan. Very low resolution or handwritten scores may not work |
| Connection refused on physical device | Use your computer's LAN IP (e.g. `192.168.1.x`), not `localhost` |
