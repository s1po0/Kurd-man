#!/usr/bin/env python3
"""Offline Koorix review tools. No translation/model/network/shell execution.

Public output is fixed codes, indices and counters. Detailed content is written
only to an explicitly selected private file. Exit 2 means review is still needed,
not that a translator/model has been evaluated automatically.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import unicodedata as ud
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PACKET = "koorix.packet.v1"
REVIEW = "koorix.review.v1"
GENRES = {"AUTO", "FANTASY", "MARTIAL", "EVERYDAY"}
REVIEWERS = {"unassigned", "same_agent", "independent_agent", "human"}
CATEGORIES = {"fabrication", "omission", "negation", "condition", "roles", "tense",
              "tone", "name", "term", "number", "ocr", "structure", "visual", "other"}
FORBIDDEN = {".git", "signing", "signing-recovery", "state", "node_modules",
             ".ssh", ".aws", ".gnupg", ".azure", ".kube", ".env"}
MAX_BYTES = 20 * 1024 * 1024
JAVA_TRIM = "".join(chr(i) for i in range(33))
RESERVED = re.compile(r"ZXQK|\[\[KX", re.I | re.A)
BUNDLE_FILES = (
    "SKILL.md", "LICENSE", "README_KU.md", "PROVENANCE.md",
    "references/project-map.md", "references/meaning-review.md",
    "references/story-rules.md", "references/offline-tools.md",
    "references/safety-and-release.md", "assets/evaluation-cases.json",
    "assets/glossary-contract-cases.json", "assets/glossary-example.txt",
    "scripts/quality.py", "tests/test_quality.py", "tests/GlossaryParity.java",
)


class Invalid(Exception):
    """Only a fixed, content-free code may reach public diagnostics."""


def require(condition, code):
    if not condition:
        raise Invalid(code)


def canonical(value):
    return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":")).encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def utf16(value):
    try:
        return len(value.encode("utf-16-le")) // 2
    except UnicodeError:
        raise Invalid("INVALID_UNICODE") from None


def text(value, maximum=10000):
    require(isinstance(value, str), "TEXT_TYPE")
    require(utf16(value) <= maximum, "TEXT_LIMIT")
    return value


def clean(value):
    # Match ComicTranslationSkill.clean, including Java's ASCII whitespace/trim.
    value = ud.normalize("NFKC", value).replace("\u2019", "'").replace("\u2018", "'").replace("\u00ad", "")
    return re.sub(r"[ \t\n\x0b\f\r]+", " ", value).strip(JAVA_TRIM)


def language(value):
    require(isinstance(value, str) and re.fullmatch(r"[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*", value), "LANGUAGE_TAG")
    return value


def protected_path(path):
    path = Path(path).expanduser().resolve()
    require(not ({part.lower() for part in path.parts} & FORBIDDEN), "PROTECTED_PATH")
    require(not path.name.lower().startswith(".env") and path.name.lower() not in {"credentials.json", "bundle.json", ".netrc", ".git-credentials", ".npmrc", ".pypirc"}, "PROTECTED_PATH")
    require(path.suffix.lower() not in {".p12", ".pfx", ".keystore", ".jks", ".pem"}, "PROTECTED_PATH")
    return path


def repo_root(origin=None):
    origins = (Path(origin).resolve(),) if origin is not None else (Path.cwd().resolve(), ROOT)
    for origin in origins:
        for parent in (origin, *origin.parents):
            if (parent / ".git").exists():
                return parent
    return None


def read(path, as_json=True):
    path = protected_path(path)
    require(path.is_file() and path.stat().st_size <= MAX_BYTES, "INPUT_MISSING_OR_TOO_LARGE")
    try:
        with path.open("rb") as stream:
            raw = stream.read(MAX_BYTES + 1)
        require(len(raw) <= MAX_BYTES, "INPUT_MISSING_OR_TOO_LARGE")
        value = raw.decode("utf-8")
        return json.loads(value) if as_json else value
    except (UnicodeError, ValueError):
        raise Invalid("INPUT_ENCODING_OR_JSON") from None


def write(path, value, *, sensitive=False):
    path = protected_path(path)
    require(not path.exists() and not path.is_symlink(), "OUTPUT_EXISTS")
    repo = repo_root(path.parent)
    if sensitive and repo is not None:
        try:
            relative = path.relative_to(repo)
        except ValueError:
            relative = None
        require(relative is None or relative.parts[0] == ".private", "PRIVATE_OUTPUT_REQUIRED")
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    data = (json.dumps(value, ensure_ascii=False, indent=2) + "\n") if not isinstance(value, str) else value
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as stream:
        stream.write(data)


def glossary(value):
    text(value, 12000)
    entries, seen = [], set()
    for raw in re.split(r"\r?\n", value):
        line = raw.strip(JAVA_TRIM)
        if not line or line.startswith("#"):
            continue
        require("=" in line and line.index("=") > 0, "GLOSSARY_DELIMITER")
        source, target = (clean(part) for part in line.split("=", 1))
        require(source and target and utf16(source) <= 80 and utf16(target) <= 160, "GLOSSARY_ENTRY_LIMIT")
        require(not RESERVED.search(line), "GLOSSARY_RESERVED_MARKER")
        require(source.lower() not in seen, "GLOSSARY_DUPLICATE")
        require(len(entries) < 100, "GLOSSARY_COUNT_LIMIT")
        seen.add(source.lower())
        entries.append((source, target))
    return entries


def validate(packet):
    require(isinstance(packet, dict) and set(packet) == {
        "schema", "source_language", "target_language", "genre", "glossary", "pages"}, "PACKET_SCHEMA")
    require(packet["schema"] == PACKET, "PACKET_VERSION")
    language(packet["source_language"])
    language(packet["target_language"])
    require(packet["genre"] in GENRES, "GENRE")
    glossary(packet["glossary"])
    pages = packet["pages"]
    require(isinstance(pages, list) and 1 <= len(pages) <= 300, "PAGE_LIMIT")
    for i, page in enumerate(pages):
        require(isinstance(page, dict) and set(page) == {"id", "genre", "segments"}, "PAGE_SCHEMA")
        require(page["id"] == f"p{i+1:04d}" and page["genre"] in GENRES, "PAGE_ID_OR_GENRE")
        require(isinstance(page["segments"], list) and len(page["segments"]) <= 1000, "SEGMENT_LIMIT")
        for j, item in enumerate(page["segments"]):
            require(isinstance(item, dict) and set(item) == {"id", "source", "target", "kind"}, "SEGMENT_SCHEMA")
            require(item["id"] == f"b{j+1:04d}", "SEGMENT_ID")
            require(item["kind"] in {"unknown", "dialogue", "narration", "sfx"}, "SEGMENT_KIND")
            text(item["source"])
            text(item["target"])
    return packet


def project_packet(project):
    require(isinstance(project, dict) and type(project.get("version")) is int and project["version"] == 1, "PROJECT_VERSION")
    config = project.get("comicSkill")
    config = {} if config is None else config
    require(isinstance(config, dict), "PROJECT_CONFIG")
    packet = {"schema": PACKET, "source_language": project.get("sourceLanguage", "en"),
              "target_language": project.get("targetLanguage", "ckb"),
              "genre": config.get("genre", "AUTO"), "glossary": config.get("glossary", ""), "pages": []}
    pages = project.get("pages")
    require(isinstance(pages, list) and 1 <= len(pages) <= 300, "PAGE_LIMIT")
    for i, page in enumerate(pages):
        require(isinstance(page, dict) and isinstance(page.get("segments"), list), "PROJECT_PAGE")
        require(len(page["segments"]) <= 1000, "SEGMENT_LIMIT")
        result = {"id": f"p{i+1:04d}", "genre": packet["genre"], "segments": []}
        for j, segment in enumerate(page["segments"]):
            require(isinstance(segment, dict), "PROJECT_SEGMENT")
            for field in ("text", "ckb"):
                require(segment.get(field) is None or isinstance(segment[field], str), "PROJECT_TEXT_TYPE")
            require(segment.get("outsideText") is None or type(segment["outsideText"]) is bool, "PROJECT_KIND_TYPE")
            result["segments"].append({"id": f"b{j+1:04d}", "source": segment.get("text") or "",
                                       "target": segment.get("ckb") or "",
                                       "kind": "sfx" if segment.get("outsideText") is True else "unknown"})
        packet["pages"].append(result)
    # All project titles, image paths, boxes, styles, metadata and unknown fields
    # are ignored. No sourcePath is opened and the project is never modified.
    return validate(packet)


def cases():
    value = read(ROOT / "assets/evaluation-cases.json")
    require(isinstance(value, dict) and value.get("schema") == "koorix.evaluation.v1", "FIXTURE_SCHEMA")
    require(value.get("synthetic") is True and value.get("reference_translations") is False, "FIXTURE_CLAIMS")
    items = value.get("cases")
    require(isinstance(items, list) and len(items) == 20, "FIXTURE_COUNT")
    ids, pairs = set(), set()
    for item in items:
        require(isinstance(item, dict), "FIXTURE_CASE")
        require(re.fullmatch(r"[a-z0-9-]{1,64}", item.get("id", "")), "FIXTURE_ID")
        require(item["id"] not in ids, "FIXTURE_DUPLICATE")
        ids.add(item["id"])
        language(item["source_language"])
        require(item["genre"] in GENRES and isinstance(item["bubbles"], list) and 2 <= len(item["bubbles"]) <= 4, "FIXTURE_CONTEXT")
        for bubble in item["bubbles"]:
            require(bool(text(bubble)), "FIXTURE_EMPTY")
        pairs.add((item["source_language"], item["genre"]))
    require(pairs == {(lang, genre) for lang in ("en", "ko", "ja", "zh-CN", "zh-TW") for genre in GENRES}, "FIXTURE_COVERAGE")
    return items


def fixture_packet(source, target):
    language(source)
    language(target)
    items = [item for item in cases() if item["source_language"] == source]
    require(items, "FIXTURE_SOURCE_UNAVAILABLE")
    packet = {"schema": PACKET, "source_language": source, "target_language": target,
              "genre": "AUTO", "glossary": "", "pages": []}
    for i, item in enumerate(items):
        packet["pages"].append({"id": f"p{i+1:04d}", "genre": item["genre"], "segments": [
            {"id": f"b{j+1:04d}", "source": source_text, "target": "", "kind": "dialogue"}
            for j, source_text in enumerate(item["bubbles"])]})
    return validate(packet)


def numbers(value):
    normalized = []
    for char in value:
        try:
            normalized.append(str(ud.decimal(char)))
        except ValueError:
            if char == "\u066c":
                continue
            normalized.append({"\u066b": ".", "\u2212": "-", "\u066a": "%"}.get(char, char))
    value = re.sub(r"(?<=\d),(?=\d{3}(?:\D|$))", "", "".join(normalized))
    # Ordered tokens intentionally catch 25/100 -> 100/25 and % loss, which a
    # global digit multiset cannot. These remain REVIEW hints, not semantics.
    return [re.sub(r"\s+%", "%", token) for token in re.findall(r"[-+]?\d+(?:[.,]\d+)*(?:\s*%)?", value)]


def wordchar(char):
    return char == "_" or ud.category(char)[0] in {"L", "N"}


def term_hits(source, entries):
    source = clean(source)
    taken, hits = bytearray(len(source)), []
    for original, target in sorted(entries, key=lambda pair: utf16(pair[0]), reverse=True):
        latin = any(c.isascii() and c.isalpha() for c in original)
        for match in re.finditer(re.escape(original), source, flags=re.I):
            start, end = match.span()
            if latin and ((start and wordchar(source[start-1])) or (end < len(source) and wordchar(source[end]))):
                continue
            if any(taken[start:end]):
                continue
            taken[start:end] = b"\x01" * (end-start)
            hits.append(target)
    return hits


def check(source, candidate):
    validate(source)
    validate(candidate)
    issues, totals = [], Counter()
    def issue(code, page=-1, segment=-1, severity="review"):
        totals[severity] += 1
        if len(issues) < 1000:
            issues.append({"code": code, "page": page, "segment": segment, "severity": severity})
    for field in ("source_language", "target_language", "genre", "glossary"):
        if source[field] != candidate[field]:
            issue("SOURCE_SETTINGS_CHANGED", severity="block")
    if len(source["pages"]) != len(candidate["pages"]):
        issue("PAGE_COUNT_CHANGED", severity="block")
    count = 0
    entries = glossary(source["glossary"])
    target_base = source["target_language"].split("-")[0]
    for pi, (left, right) in enumerate(zip(source["pages"], candidate["pages"])):
        if left["genre"] != right["genre"]:
            issue("PAGE_GENRE_CHANGED", pi, severity="block")
        if len(left["segments"]) != len(right["segments"]):
            issue("SEGMENT_COUNT_CHANGED", pi, severity="block")
        for si, (before, after) in enumerate(zip(left["segments"], right["segments"])):
            count += 1
            if before["source"] != after["source"] or before["kind"] != after["kind"]:
                issue("SOURCE_OR_KIND_CHANGED", pi, si, "block")
            original, output = before["source"], after["target"]
            if not original.strip():
                if output.strip():
                    issue("ADDED_TEXT_TO_EMPTY_SOURCE", pi, si, "block")
                continue
            if not output.strip():
                issue("EMPTY_TARGET", pi, si, "block")
                continue
            if RESERVED.search(clean(output)):
                issue("LEAKED_RUNTIME_MARKER", pi, si, "block")
            a, b = numbers(original), numbers(output)
            if Counter(a) != Counter(b):
                issue("NUMBER_VALUE_OR_UNIT_CHANGED", pi, si)
            elif a != b:
                issue("NUMBER_ORDER_CHANGED", pi, si)
            for preferred, expected in Counter(term_hits(original, entries)).items():
                if len(re.findall(re.escape(clean(preferred)), clean(output), flags=re.I)) < expected:
                    issue("PREFERRED_TERM_MISSING", pi, si)
            allowed_literal = any(clean(original).lower() == name.lower() and clean(output) == preferred for name, preferred in entries)
            same_language = source["source_language"].split("-")[0] == target_base
            if not allowed_literal and not same_language and clean(original).lower() == clean(output).lower() and any(c.isspace() for c in original):
                issue("SOURCE_ECHO", pi, si)
            if not allowed_literal and target_base in {"ckb", "ar", "fa"}:
                letters = [c for c in output if ud.category(c).startswith("L")]
                if len(letters) >= 4 and not any("ARABIC" in ud.name(c, "") for c in letters):
                    issue("TARGET_SCRIPT_UNCONFIRMED", pi, si)
            words = re.findall(r"[^\W\d_]+", clean(output).lower(), re.U)
            source_words = re.findall(r"[^\W\d_]+", clean(original).lower(), re.U)
            original_repeated = any(len(set(source_words[i:i+4])) == 1 for i in range(max(0, len(source_words)-3)))
            if not original_repeated and any(len(set(words[i:i+4])) == 1 for i in range(max(0, len(words)-3))):
                issue("REPETITION", pi, si)
    blocks = totals["block"]
    return {"schema": "koorix.contract-report.v1", "status": "blocked" if blocks else "needs_review" if issues else "contracts_clear",
            "meaning": "not_assessed", "visual": "not_assessed", "pages": len(source["pages"]),
            "segments_checked": count, "blocking_issues": blocks, "review_hints": totals["review"], "issues_truncated": sum(totals.values())-len(issues), "issues": issues}


def positions(packet):
    return [(pi, si) for pi, page in enumerate(packet["pages"]) for si, item in enumerate(page["segments"]) if item["source"].strip()]


def review_template(packet):
    validate(packet)
    return {"schema": REVIEW, "candidate_sha256": digest(packet), "reviewer_kind": "unassigned", "segments": [
        {"page": pi, "segment": si, "verdict": "unreviewed", "context_sufficient": False,
         "meaning": None, "naturalness": None, "terminology": None, "issues": [], "notes": ""}
        for pi, si in positions(packet)]}


def score(packet, review):
    validate(packet)
    require(isinstance(review, dict) and set(review) == {"schema", "candidate_sha256", "reviewer_kind", "segments"}, "REVIEW_SCHEMA")
    require(review["schema"] == REVIEW and review["candidate_sha256"] == digest(packet), "STALE_OR_WRONG_REVIEW")
    require(review["reviewer_kind"] in REVIEWERS, "REVIEWER_KIND")
    rows = review["segments"]
    require(isinstance(rows, list) and len(rows) == len(positions(packet)), "REVIEW_COVERAGE")
    counts, categories = Counter(), Counter()
    for row, expected in zip(rows, positions(packet)):
        require(isinstance(row, dict) and set(row) == {"page", "segment", "verdict", "context_sufficient", "meaning", "naturalness", "terminology", "issues", "notes"}, "REVIEW_ROW_SCHEMA")
        require(type(row["page"]) is int and type(row["segment"]) is int and (row["page"], row["segment"]) == expected, "REVIEW_ORDER_OR_DUPLICATE")
        verdict = row["verdict"]
        require(verdict in {"unreviewed", "pass", "revise", "uncertain"} and type(row["context_sufficient"]) is bool, "REVIEW_VERDICT")
        text(row["notes"], 2000)
        for field in ("meaning", "naturalness", "terminology"):
            require(row[field] is None or (type(row[field]) is int and 0 <= row[field] <= 4), "REVIEW_SCORE")
        require(isinstance(row["issues"], list) and len(row["issues"]) <= 50, "REVIEW_ISSUES")
        severities = []
        for item in row["issues"]:
            require(isinstance(item, dict) and set(item) == {"category", "severity", "note"}, "REVIEW_ISSUE_SCHEMA")
            require(item["category"] in CATEGORIES and item["severity"] in {"minor", "major", "critical"}, "REVIEW_ISSUE_CATEGORY")
            text(item["note"], 2000)
            categories[item["category"]] += 1
            severities.append(item["severity"])
        if verdict == "pass":
            require(review["reviewer_kind"] != "unassigned" and row["context_sufficient"]
                    and all(type(row[key]) is int and row[key] >= 3 for key in ("meaning", "naturalness", "terminology"))
                    and all(level == "minor" for level in severities), "UNSUPPORTED_PASS")
            require(bool(packet["pages"][row["page"]]["segments"][row["segment"]]["target"].strip()), "EMPTY_TARGET_CANNOT_PASS")
        counts[verdict] += 1
    complete = bool(rows) and counts["unreviewed"] == 0
    accepted = complete and counts["pass"] == len(rows)
    contracts = check(packet, packet)
    return {"schema": "koorix.review-summary.v1", "assessment": "declared_reviewer_records_only",
            "reviewer_kind": review["reviewer_kind"], "reviewer_identity_verified": False,
            "semantic_accuracy_proven": False, "visual": "not_assessed", "candidate_binding_verified": True,
            "complete": complete, "all_declared_pass": accepted, "contract_status": contracts["status"],
            "contract_review_hints": contracts["review_hints"], "contract_blocking_issues": contracts["blocking_issues"], "segments": len(rows),
            "verdicts": {key: counts[key] for key in ("unreviewed", "pass", "revise", "uncertain")},
            "issue_categories": dict(sorted(categories.items()))}


def self_check():
    main = read(ROOT / "SKILL.md", as_json=False)
    require(main.startswith("---\n") and "\n---\n" in main[4:], "SKILL_FRONTMATTER")
    metadata = main.split("---\n", 2)[1]
    require("name: koorix-comic-quality\n" in metadata and "description: " in metadata and "license: MIT\n" in metadata, "SKILL_METADATA")
    require(len(main.splitlines()) <= 500, "SKILL_PROGRESSIVE_DISCLOSURE")
    for relative in BUNDLE_FILES:
        path = ROOT / relative
        require(path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(ROOT), "BUNDLE_RESOURCE_MISSING_OR_LINKED")
        require(path.stat().st_size < 2 * 1024 * 1024, "BUNDLE_RESOURCE_LIMIT")
        content = read(path, as_json=False)
        for pattern in (r"-----BEGIN (?:RSA |EC |ENCRYPTED )?PRIVATE KEY-----",
                        r"\b[0-9]{8,15}:[A-Za-z0-9_-]{30,}\b", r"sb_secret_[A-Za-z0-9_-]{20,}"):
            require(not re.search(pattern, content), "BUNDLE_SUSPECTED_SECRET")
    for target in re.findall(r"\]\(([^)]+)\)", main):
        require(not target.startswith(("http:", "https:")) and (ROOT / target).is_file(), "SKILL_REFERENCE")
    items = cases()
    return {"status": "ok", "skill": "koorix-comic-quality", "version": "1.0.0", "public_resources": len(BUNDLE_FILES),
            "synthetic_cases": len(items), "reference_translations": False, "network_or_model_used": False}


def bundle(out):
    self_check()
    out = protected_path(out)
    require(out.suffix.lower() == ".zip" and not out.exists(), "BUNDLE_OUTPUT")
    out.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    hashes = {}
    with zipfile.ZipFile(out, "x", zipfile.ZIP_DEFLATED) as archive:
        for relative in BUNDLE_FILES:
            data = (ROOT / relative).read_bytes()
            hashes[relative] = hashlib.sha256(data).hexdigest()
            info = zipfile.ZipInfo("koorix-comic-quality/" + relative, date_time=(2026, 11, 6, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
        info = zipfile.ZipInfo("koorix-comic-quality/manifest.json", date_time=(2026, 11, 6, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o100644 << 16
        archive.writestr(info, json.dumps({"schema": "koorix.skill-bundle.v1", "version": "1.0.0", "files": hashes}, indent=2) + "\n")
    return {"status": "ok", "entries": len(BUNDLE_FILES)+1, "bytes": out.stat().st_size,
            "sha256": hashlib.sha256(out.read_bytes()).hexdigest(), "private_paths_excluded": True, "secret_marker_scan_passed": True}


class SafeParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse normally echoes unknown argument values, which can be private.
        raise Invalid("CLI_ARGUMENTS")


def main(argv=None):
    parser = SafeParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("self-check")
    p = commands.add_parser("fixtures")
    p.add_argument("--source-language", required=True)
    p.add_argument("--target-language", required=True)
    p.add_argument("--out", required=True)
    p = commands.add_parser("packet")
    p.add_argument("--project", required=True)
    p.add_argument("--out", required=True)
    p = commands.add_parser("glossary")
    p.add_argument("--input", required=True)
    p.add_argument("--source-language", required=True)
    p.add_argument("--target-language", required=True)
    p.add_argument("--out")
    p = commands.add_parser("check")
    p.add_argument("--source", required=True)
    p.add_argument("--candidate", required=True)
    p.add_argument("--report")
    p = commands.add_parser("review-template")
    p.add_argument("--candidate", required=True)
    p.add_argument("--out", required=True)
    p = commands.add_parser("score")
    p.add_argument("--candidate", required=True)
    p.add_argument("--review", required=True)
    p.add_argument("--report")
    p = commands.add_parser("bundle")
    p.add_argument("--out", required=True)
    try:
        args = parser.parse_args(argv)
        status = 0
        if args.command == "self-check":
            result = self_check()
        elif args.command == "fixtures":
            packet = fixture_packet(args.source_language, args.target_language)
            write(args.out, packet, sensitive=True)
            result = {"status": "ok", "pages": len(packet["pages"]), "targets": "empty_not_translated"}
        elif args.command == "packet":
            packet = project_packet(read(args.project))
            write(args.out, packet, sensitive=True)
            result = {"status": "ok", "pages": len(packet["pages"]), "geometry_or_app_data_modified": False}
        elif args.command == "glossary":
            language(args.source_language)
            language(args.target_language)
            value = read(args.input, as_json=False)
            entries = glossary(value)
            if args.out:
                write(args.out, value, sensitive=True)
            result = {"status": "ok", "entries": len(entries), "source_language": args.source_language,
                      "target_language": args.target_language, "in_app_import_added": False}
        elif args.command == "check":
            result = check(read(args.source), read(args.candidate))
            status = 0 if result["status"] == "contracts_clear" else 2
            if args.report:
                write(args.report, result)
        elif args.command == "review-template":
            result = review_template(read(args.candidate))
            write(args.out, result, sensitive=True)
            result = {"status": "ok", "reviewed": 0, "unreviewed": len(result["segments"])}
        elif args.command == "score":
            result = score(read(args.candidate), read(args.review))
            status = 0 if result["all_declared_pass"] and result["contract_status"] == "contracts_clear" else 2
            if args.report:
                write(args.report, result)
        else:
            result = bundle(args.out)
        print(json.dumps(result, ensure_ascii=True, sort_keys=True))
        return status
    except Invalid as error:
        print(json.dumps({"status": "invalid", "code": str(error)}))
    except (OSError, ValueError, TypeError, KeyError, AttributeError, IndexError, OverflowError, RuntimeError):
        # No tracebacks, file paths, JSON excerpts, dialogue or private values.
        print(json.dumps({"status": "invalid", "code": "INPUT_OR_IO_ERROR"}))
    return 1


if __name__ == "__main__":
    sys.exit(main())
