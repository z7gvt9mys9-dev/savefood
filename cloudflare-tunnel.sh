#!/bin/bash

LOG=/tmp/cloudflared.log
URL_FILE="$HOME/savefood-url.txt"

cloudflared tunnel --url http://localhost:80 2>&1 | tee "$LOG" | while IFS= read -r line; do
    if [[ "$line" == *"trycloudflare.com"* ]]; then
        url=$(echo "$line" | grep -o "https://[a-z0-9\-]*\.trycloudflare\.com")
        if [[ -n "$url" ]]; then
            echo "$url" > "$URL_FILE"
            echo "$(date): $url" >> "${URL_FILE}.history"
        fi
    fi
done
