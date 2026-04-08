FROM python:3.11-slim

# poppler-utils is needed by pdf2image to convert PDF pages to images
RUN apt-get update && \
    apt-get install -y --no-install-recommends poppler-utils && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY omr_server.py .

EXPOSE 5000
CMD ["python", "omr_server.py", "--host", "0.0.0.0", "--port", "5000"]
