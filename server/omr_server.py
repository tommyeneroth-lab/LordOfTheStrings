"""
Lord of the Strings — OMR Server

A lightweight Flask server that accepts PDF or image files (base64-encoded),
runs Optical Music Recognition via oemer, and returns MusicXML.

API:
    POST /omr     — { "pdf_base64": "<base64>", "filename": "score.pdf" }
                    Returns { "musicxml": "<MusicXML string>" }
    GET  /health  — Returns { "status": "ok" }

Usage:
    python omr_server.py                     # http://0.0.0.0:5000
    python omr_server.py --port 8080         # custom port
    python omr_server.py --host 127.0.0.1    # localhost only
"""

import argparse
import base64
import logging
import os
import subprocess
import sys
import tempfile
from pathlib import Path

from flask import Flask, jsonify, request

app = Flask(__name__)
logging.basicConfig(level=logging.INFO, format="%(asctime)s  %(levelname)s  %(message)s")
log = logging.getLogger("omr")


def pdf_to_images(pdf_path: str, output_dir: str) -> list[str]:
    """Convert each page of a PDF to a PNG image using pdf2image (poppler)."""
    try:
        from pdf2image import convert_from_path
        images = convert_from_path(pdf_path, dpi=300, output_folder=output_dir, fmt="png")
        paths = []
        for i, img in enumerate(images):
            p = os.path.join(output_dir, f"page_{i}.png")
            img.save(p)
            paths.append(p)
        return paths
    except ImportError:
        log.error("pdf2image not installed — pip install pdf2image")
        raise


def run_oemer(image_path: str) -> str:
    """Run oemer on a single image and return the path to the output MusicXML."""
    result = subprocess.run(
        [sys.executable, "-m", "oemer", image_path],
        capture_output=True, text=True, timeout=300
    )
    if result.returncode != 0:
        log.error("oemer stderr: %s", result.stderr)
        raise RuntimeError(f"oemer failed: {result.stderr[:500]}")

    # oemer writes output next to the input file as <name>.musicxml
    base = os.path.splitext(image_path)[0]
    xml_path = base + ".musicxml"
    if not os.path.exists(xml_path):
        # Some oemer versions write to <name>_result.musicxml
        alt = base + "_result.musicxml"
        if os.path.exists(alt):
            xml_path = alt
        else:
            raise FileNotFoundError(
                f"oemer did not produce MusicXML. Checked: {xml_path}, {alt}\n"
                f"stdout: {result.stdout[:300]}"
            )
    return xml_path


def merge_musicxml(xml_paths: list[str]) -> str:
    """
    Merge multiple single-page MusicXML files into one.
    Simple strategy: take all <measure> elements from each file and renumber.
    """
    if len(xml_paths) == 1:
        return Path(xml_paths[0]).read_text(encoding="utf-8")

    import xml.etree.ElementTree as ET

    # Use the first file as the base
    base_tree = ET.parse(xml_paths[0])
    base_root = base_tree.getroot()
    ns = ""
    # Find the first <part> element
    part = base_root.find(f"{ns}part")
    if part is None:
        # Try with common MusicXML namespace
        for child in base_root:
            if "part" in child.tag and child.get("id"):
                part = child
                break

    if part is None:
        # Fallback: just return first file
        return Path(xml_paths[0]).read_text(encoding="utf-8")

    # Collect max measure number from base
    max_num = max(
        (int(m.get("number", 0)) for m in part.findall(f"{ns}measure")),
        default=0
    )

    # Append measures from subsequent pages
    for xml_path in xml_paths[1:]:
        tree = ET.parse(xml_path)
        root = tree.getroot()
        other_part = root.find(f"{ns}part")
        if other_part is None:
            for child in root:
                if "part" in child.tag and child.get("id"):
                    other_part = child
                    break
        if other_part is None:
            continue
        for measure in other_part.findall(f"{ns}measure"):
            max_num += 1
            measure.set("number", str(max_num))
            part.append(measure)

    return ET.tostring(base_root, encoding="unicode", xml_declaration=True)


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok"})


@app.route("/omr", methods=["POST"])
def omr():
    data = request.get_json(force=True)
    b64 = data.get("pdf_base64", "")
    filename = data.get("filename", "input.pdf")

    if not b64:
        return jsonify({"error": "Missing pdf_base64 field"}), 400

    raw = base64.b64decode(b64)
    ext = os.path.splitext(filename)[1].lower() or ".pdf"
    log.info("Received %s (%d bytes)", filename, len(raw))

    with tempfile.TemporaryDirectory(prefix="omr_") as tmpdir:
        input_path = os.path.join(tmpdir, f"input{ext}")
        with open(input_path, "wb") as f:
            f.write(raw)

        # If PDF, convert pages to images first
        if ext == ".pdf":
            log.info("Converting PDF to images...")
            image_paths = pdf_to_images(input_path, tmpdir)
        else:
            image_paths = [input_path]

        log.info("Running oemer on %d page(s)...", len(image_paths))
        xml_paths = []
        for i, img_path in enumerate(image_paths):
            log.info("  Page %d/%d: %s", i + 1, len(image_paths), img_path)
            xml_path = run_oemer(img_path)
            xml_paths.append(xml_path)
            log.info("  -> %s", xml_path)

        musicxml = merge_musicxml(xml_paths)
        log.info("Done — MusicXML length: %d chars", len(musicxml))

    return jsonify({"musicxml": musicxml})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Lord of the Strings OMR Server")
    parser.add_argument("--host", default="0.0.0.0", help="Bind address (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=5000, help="Port (default: 5000)")
    parser.add_argument("--debug", action="store_true", help="Enable Flask debug mode")
    args = parser.parse_args()

    log.info("Starting OMR server on %s:%d", args.host, args.port)
    app.run(host=args.host, port=args.port, debug=args.debug)
