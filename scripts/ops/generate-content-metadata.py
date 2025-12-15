#!/usr/bin/env python3

import argparse
import dataclasses
import hashlib
import json
import os
import re
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Iterable, Optional


YEAR_RE = re.compile(r"(19|20)\d{2}")


@dataclass(frozen=True)
class Approval:
    role: str
    reference: str
    approved_at: str
    name: Optional[str] = None


@dataclass(frozen=True)
class Source:
    kind: str
    id: str
    revision_date: str
    checksum_sha256: Optional[str] = None


@dataclass(frozen=True)
class Coverage:
    year: Optional[int]
    effective_from: Optional[str]
    effective_to: Optional[str]
    jurisdictions: Optional[list[str]] = None


@dataclass(frozen=True)
class Artifact:
    path: str
    sha256: str
    media_type: str


@dataclass(frozen=True)
class ContentMetadata:
    schema_version: int
    content_id: str
    domain: str
    artifact: Artifact
    coverage: Coverage
    source: Source
    approvals: list[Approval]


def sha256_hex(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def infer_year(stem: str) -> Optional[int]:
    m = YEAR_RE.search(stem)
    if not m:
        return None
    return int(m.group(0))


def infer_media_type(path: Path) -> str:
    if path.suffix == ".json":
        return "application/json"
    if path.suffix == ".csv":
        return "text/csv"
    if path.suffix == ".sql":
        return "application/sql"
    return "application/octet-stream"


def default_effective_range(year: Optional[int]) -> tuple[Optional[str], Optional[str]]:
    if year is None:
        return (None, None)
    return (f"{year}-01-01", "9999-12-31")


def make_content_id(domain: str, rel_path: str) -> str:
    stem = Path(rel_path).name
    stem = re.sub(r"\.(json|csv|sql)$", "", stem, flags=re.IGNORECASE)
    token = re.sub(r"[^A-Za-z0-9]+", "_", stem).upper().strip("_")
    return f"{domain.upper()}_{token}"


def to_json_dict(meta: ContentMetadata) -> dict:
    return {
        "schemaVersion": meta.schema_version,
        "contentId": meta.content_id,
        "domain": meta.domain,
        "artifact": dataclasses.asdict(meta.artifact),
        "coverage": dataclasses.asdict(meta.coverage),
        "source": dataclasses.asdict(meta.source),
        "approvals": [dataclasses.asdict(a) for a in meta.approvals],
    }


def write_metadata_for(root: Path, path: Path, domain: str, revision_date: str, source_id: Optional[str]) -> None:
    rel_path = path.relative_to(root).as_posix()
    year = infer_year(path.stem)
    effective_from, effective_to = default_effective_range(year)

    content_id = make_content_id(domain, rel_path)
    inferred_source_id = f"SRC_{content_id}"

    meta = ContentMetadata(
        schema_version=1,
        content_id=content_id,
        domain=domain,
        artifact=Artifact(
            path=rel_path,
            sha256=sha256_hex(path),
            media_type=infer_media_type(path),
        ),
        coverage=Coverage(
            year=year,
            effective_from=effective_from,
            effective_to=effective_to,
            jurisdictions=None,
        ),
        source=Source(
            kind="INTERNAL_OR_CURATED_SOURCE",
            id=source_id or inferred_source_id,
            revision_date=revision_date,
            checksum_sha256=None,
        ),
        approvals=[
            Approval(
                role="ENGINEERING",
                reference="initial-metadata-standard",
                approved_at=revision_date,
                name=os.getenv("USER"),
            ),
        ],
    )

    # Sidecar naming includes the original extension to avoid collisions (e.g. foo.csv and foo.json).
    metadata_path = path.with_name(path.name + ".metadata.json")
    metadata_path.write_text(
        json.dumps(to_json_dict(meta), indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def iter_files(root: Path, globs: Iterable[str]) -> list[Path]:
    out: list[Path] = []
    for g in globs:
        out.extend(sorted(root.glob(g)))
    # De-dupe while preserving order
    seen: set[str] = set()
    uniq: list[Path] = []
    for p in out:
        key = p.as_posix()
        if key in seen:
            continue
        seen.add(key)
        uniq.append(p)
    return uniq


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, help="repo root")
    parser.add_argument("--domain", required=True, choices=["tax", "labor"], help="metadata domain")
    parser.add_argument("--revision-date", default=str(date.today()), help="YYYY-MM-DD")
    parser.add_argument(
        "--source-id",
        default=None,
        help="Optional stable source id for all generated metadata; defaults to SRC_<contentId>.",
    )
    parser.add_argument("--glob", action="append", required=True, help="glob relative to root; can be repeated")

    args = parser.parse_args()
    root = Path(args.root).resolve()

    files = iter_files(root, args.glob)
    for f in files:
        if f.is_dir():
            continue
        if f.name.endswith(".metadata.json"):
            continue
        write_metadata_for(root, f, args.domain, args.revision_date, args.source_id)


if __name__ == "__main__":
    main()
