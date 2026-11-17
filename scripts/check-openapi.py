#!/usr/bin/env python3
"""Fails if the API and contracts/openapi/platform-v1.yaml list different endpoints.

Reads every Spring controller's class-level @RequestMapping and method-level @Get/Post/Put/Patch/DeleteMapping
and compares (method, path) pairs with the contract's paths. Path variable names are ignored.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONTRACT = ROOT / "contracts" / "openapi" / "platform-v1.yaml"
SOURCES = ROOT / "backend" / "src" / "main" / "java"
VERB = re.compile(r'@(Get|Post|Put|Patch|Delete)Mapping(?:\(([^)]*)\))?')
BASE = re.compile(r'@RequestMapping\(\s*(?:(?:value|path)\s*=\s*)?"([^"]*)"')


def norm(path):
    return re.sub(r"\{[^}]+\}", "{}", path.rstrip("/") or "/")


def from_code():
    found = {}
    for file in SOURCES.rglob("*.java"):
        text = file.read_text()
        if "@RestController" not in text and "@Controller" not in text:
            continue
        head = text.split(" class ", 1)[0]
        base = BASE.search(head)
        prefix = base.group(1) if base else ""
        for m in VERB.finditer(text):
            args = (m.group(2) or "").strip()
            args = re.sub(r"^(?:value|path)\s*=\s*", "", args)
            if args.startswith("{"):
                paths = re.findall(r'"([^"]*)"', args[: args.index("}")])
            else:
                first = re.match(r'"([^"]*)"', args)
                paths = [first.group(1) if first else ""]
            for path in paths:
                found[(m.group(1).upper(), norm(prefix + path))] = file.relative_to(ROOT)
    return found


def from_contract():
    found, current = set(), None
    for line in CONTRACT.read_text().splitlines():
        path = re.match(r"^  (/\S*):", line)
        if path:
            current = path.group(1)
            continue
        verb = re.match(r"^    (get|post|put|patch|delete):", line)
        if verb and current:
            found.add((verb.group(1).upper(), norm(current)))
    return found


code, contract = from_code(), from_contract()
missing = sorted(set(code) - contract)
extra = sorted(contract - set(code))
for method, path in missing:
    print(f"In the API but not in the contract: {method} {path} ({code[(method, path)]})")
for method, path in extra:
    print(f"In the contract but not in the API: {method} {path}")
if missing or extra:
    sys.exit(1)
print(f"OpenAPI contract matches the API: {len(code)} endpoints.")
