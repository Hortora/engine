#!/usr/bin/env python3
"""Tag untagged garden entries using title/domain/body keyword matching."""

import os
import re
import yaml
from collections import Counter
from pathlib import Path

GARDEN = Path(os.path.expanduser("~/.hortora/garden"))
SKIP = {"GARDEN.md", "CHECKED.md", "DISCARDED.md", "INDEX.md"}

COMPOUND_TAGS = {
    "webauthn": ["webauthn", "authentication"],
    "web authn": ["webauthn", "authentication"],
    "smallrye": ["smallrye"],
    "github actions": ["github-actions", "ci"],
    "github packages": ["github-packages"],
    "lazy loading": ["lazy-loading"],
    "lazy-loading": ["lazy-loading"],
    "silent failure": ["silent-failure"],
    "silently": ["silent-failure"],
    "silently ignored": ["silent-failure"],
    "silently skips": ["silent-failure"],
    "silently fails": ["silent-failure"],
    "silently breaks": ["silent-failure"],
    "multi-module": ["multi-module"],
    "multi module": ["multi-module"],
    "build profile": ["build-profile"],
    "build time": ["build-time"],
    "dev services": ["dev-services"],
    "dev mode": ["dev-mode"],
    "quarkus:dev": ["dev-mode"],
    "hot reload": ["hot-reload"],
    "live reload": ["hot-reload"],
    "native image": ["native-image", "graalvm"],
    "rest client": ["rest-client"],
    "config property": ["configuration"],
    "config expression": ["configuration"],
    "application.properties": ["configuration"],
    "pom.xml": ["maven"],
    "class cast": ["classcast"],
    "classcastexception": ["classcast"],
    "lazyinitializationexception": ["lazy-loading", "hibernate"],
    "unsatisfied dependency": ["cdi", "dependency-injection"],
    "session cookie": ["session", "security"],
    "encryption key": ["security", "encryption"],
    "login page": ["authentication"],
    "byte code": ["bytecode"],
    "bytecode": ["bytecode"],
    "onnx": ["onnx", "machine-learning"],
    "onnx runtime": ["onnx-runtime", "machine-learning"],
    "bge-m3": ["bge-m3", "embedding"],
    "embedding": ["embedding"],
    "qdrant": ["qdrant", "vector-database"],
    "mcp server": ["mcp"],
    "intellij": ["intellij"],
    "git worktree": ["git", "worktree"],
    "snapshot": ["snapshot", "maven"],
    "snapshot version": ["snapshot", "maven"],
}

WORD_TAGS = {
    "cdi": "cdi", "arc": "arc", "jpa": "jpa", "hibernate": "hibernate",
    "panache": "panache", "flyway": "flyway", "migration": "migration",
    "maven": "maven", "jackson": "jackson", "vertx": "vertx",
    "reactive": "reactive", "mutiny": "mutiny", "resteasy": "resteasy",
    "websocket": "websocket", "sse": "sse", "grpc": "grpc",
    "transaction": "transaction", "jta": "jta", "scheduler": "scheduler",
    "async": "async", "concurrency": "concurrency",
    "interceptor": "interceptor", "decorator": "decorator",
    "observer": "observer", "lifecycle": "lifecycle",
    "bean": "cdi", "beans": "cdi", "scope": "cdi",
    "producer": "cdi", "alternative": "alternative", "qualifier": "cdi",
    "augmentation": "augmentation", "docker": "docker",
    "kubernetes": "kubernetes", "security": "security",
    "authentication": "authentication", "authorization": "authorization",
    "cors": "cors", "cookie": "session", "session": "session",
    "serialization": "serialization", "json": "json", "yaml": "yaml",
    "logging": "logging", "testing": "testing", "proxy": "proxy",
    "reflection": "reflection", "annotation": "annotation",
    "packaging": "packaging", "jar": "packaging", "classpath": "classpath",
    "classloader": "classloader", "deployment": "deployment",
    "configuration": "configuration", "config": "configuration",
    "cache": "caching", "caching": "caching", "routing": "routing",
    "endpoint": "endpoint", "rest": "rest", "flow": "flow",
    "workflow": "workflow", "pojo": "pojo", "plugin": "plugin",
    "extension": "extension", "spi": "spi", "git": "git",
    "rebase": "rebase", "python": "python", "typescript": "typescript",
    "playwright": "playwright", "h2": "h2", "sql": "sql",
    "database": "database", "persistence": "persistence",
}


def parse_entry(path):
    text = path.read_text()
    if not text.startswith("---"):
        return None, None, text
    close = text.find("\n---", 3)
    if close < 0:
        return None, None, text
    fm_block = text[4:close].strip()
    body = text[close + 4:].strip()
    try:
        fm = yaml.safe_load(fm_block)
    except Exception:
        return None, None, text
    if not isinstance(fm, dict):
        return None, None, text
    return fm, body, text


def suggest_tags(title, domain, body, existing_tags_set):
    tags = set()
    if domain:
        tags.add(domain.lower())

    searchable = (title or "").lower() + " " + (body or "")[:500].lower()

    for pattern, tag_list in COMPOUND_TAGS.items():
        if pattern in searchable:
            tags.update(tag_list)

    title_lower = (title or "").lower()
    title_words = re.findall(r"[a-z][a-z0-9._-]+", title_lower)
    for word in title_words:
        if word in WORD_TAGS:
            tags.add(WORD_TAGS[word])
        if word in existing_tags_set:
            tags.add(word)

    body_words = re.findall(r"[a-z][a-z0-9._-]+", searchable)
    body_counter = Counter(body_words)
    for word, count in body_counter.items():
        if count >= 2 and word in WORD_TAGS and WORD_TAGS[word] not in tags:
            tags.add(WORD_TAGS[word])

    silent_patterns = ["silently", "no warning", "no error", "undocumented",
                       "ignored", "swallowed", "doesn't warn", "hidden"]
    for sp in silent_patterns:
        if sp in title_lower:
            tags.add("silent-failure")
            break

    return sorted(tags)


def update_frontmatter_tags(path, new_tags):
    text = path.read_text()
    close = text.find("\n---", 3)
    fm_block = text[4:close]
    after = text[close:]

    lines = fm_block.split("\n")
    new_lines = []
    skip_list_items = False
    for line in lines:
        if line.strip().startswith("tags:"):
            tag_str = ", ".join(new_tags)
            new_lines.append(f"tags: [{tag_str}]")
            skip_list_items = True
            continue
        if skip_list_items and line.startswith("  - "):
            continue
        skip_list_items = False
        new_lines.append(line)

    if not any(l.strip().startswith("tags:") for l in lines):
        insert_after = None
        for i, line in enumerate(new_lines):
            if line.strip().startswith(("score:", "type:", "domain:", "submitted:")):
                insert_after = i
        if insert_after is not None:
            tag_str = ", ".join(new_tags)
            new_lines.insert(insert_after + 1, f"tags: [{tag_str}]")
        else:
            tag_str = ", ".join(new_tags)
            new_lines.append(f"tags: [{tag_str}]")

    new_fm = "\n".join(new_lines)
    new_text = f"---\n{new_fm}{after}"
    path.write_text(new_text)


def main():
    all_tags = Counter()
    for root, dirs, files in os.walk(GARDEN):
        for f in files:
            if not f.endswith(".md") or f in SKIP:
                continue
            path = Path(root) / f
            fm, body, raw = parse_entry(path)
            if fm and isinstance(fm.get("tags"), list):
                for t in fm["tags"]:
                    all_tags[t] += 1

    existing_tags_set = set(all_tags.keys())
    updated = 0
    for root, dirs, files in os.walk(GARDEN):
        for f in sorted(files):
            if not f.endswith(".md") or f in SKIP:
                continue
            path = Path(root) / f
            fm, body, raw = parse_entry(path)
            if fm is None:
                continue

            if "tags" not in fm:
                needs_tags = True
            elif fm["tags"] is None or fm["tags"] == []:
                needs_tags = True
            else:
                needs_tags = False

            if not needs_tags:
                continue

            title = fm.get("title", "")
            domain = fm.get("domain", "")
            tags = suggest_tags(title, domain, body, existing_tags_set)

            if tags:
                update_frontmatter_tags(path, tags)
                updated += 1
                print(f"  {f:45s} -> {tags}")

    print(f"\nUpdated {updated} entries")


if __name__ == "__main__":
    main()
