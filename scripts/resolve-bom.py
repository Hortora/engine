#!/usr/bin/env python3
"""Merge user + project BOM files into pipe-separated format for gardenSearch.

Reads ~/.hortora/profile.yaml (user default) and <cwd>/.hortora/bom.yaml
(project-specific). Project wins per-key. Outputs pipe-separated format:
  quarkus:3.36.1|jdk:26.0.2|onnx-runtime:1.26.0

Exit 0 with no output if neither file exists.
"""
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    sys.exit(0)


def load_yaml(path):
    if path.exists():
        with open(path) as f:
            data = yaml.safe_load(f)
            return data if isinstance(data, dict) else {}
    return {}


user_bom = load_yaml(Path.home() / ".hortora" / "profile.yaml")
project_bom = load_yaml(Path.cwd() / ".hortora" / "bom.yaml")

merged = {**user_bom, **project_bom}
if not merged:
    sys.exit(0)

print("|".join(f"{k}:{v}" for k, v in merged.items()))
