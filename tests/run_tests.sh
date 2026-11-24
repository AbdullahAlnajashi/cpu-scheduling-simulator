#!/usr/bin/env sh
# Compiles the simulator and the tests, then runs them.
# Usage (from the project folder):  sh tests/run_tests.sh [randomFiles]
set -e
cd "$(dirname "$0")/.."
mkdir -p build/app build/tests
javac -d build/app src/*.java
javac -d build/tests tests/SimulatorTest.java
java -cp build/tests SimulatorTest build/app "${1:-0}"
