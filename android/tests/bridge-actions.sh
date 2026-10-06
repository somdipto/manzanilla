#!/bin/sh
# Regression test: every action the screen sends to the app must be accepted by the native bridge.
# (10.0.1 shipped with save_key and clear_key blocked, so the key was never saved.)
cd "$(dirname "$0")/.." || exit 1
JS=hotel/src/main/assets/reception/reception.js
KT=$(find . -name ReceptionView.kt | head -1)
allow=$(grep 'action") !in setOf' "$KT" | grep -o '"[a-z_]*"' | tr -d '"' | sort -u)
sent=$(grep -o "send('[a-z_]*'" "$JS" | sed "s/send('//; s/'//" | sort -u)
missing=""
for a in $sent; do echo "$allow" | grep -qx "$a" || missing="$missing $a"; done
# prepare is handled on the page only; native has no handler for it
missing=$(echo $missing | tr ' ' '\n' | grep -vx prepare | tr '\n' ' ')
if [ -n "$(echo $missing | tr -d ' ')" ]; then echo "FAIL: bridge blocks:$missing"; exit 1; fi
echo "OK: all sent actions are allowed by the bridge"
