#!/bin/bash
echo "[e36mc] Building Fabric Mod..."
cd mod
chmod +x gradlew
./gradlew build
read -p "Press enter to continue"
