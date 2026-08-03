import json
import hashlib
import pytest
from datetime import datetime, timezone, timedelta
from pathlib import Path
from benchmark.create_snapshot import (
    SNAPSHOT_DIR, create_manifest, list_snapshots, _compute_sha256,
    prune_snapshots,
)


def test_create_manifest_fields():
    manifest = create_manifest(
        name="test-snap",
        point_count=2400,
        qdrant_snapshot_name="hortora_garden-2026-07-29.snapshot",
        snapshot_sha256="abc123def456",
        snapshot_size_bytes=524288000,
        qdrant_version="1.12.6",
        engine_commit="7abba11",
        garden_sha="973b326a",
        scoring_sha="e8f1a2b3",
    )
    assert manifest["name"] == "test-snap"
    assert manifest["point_count"] == 2400
    assert manifest["qdrant_snapshot_name"] == "hortora_garden-2026-07-29.snapshot"
    assert manifest["snapshot_sha256"] == "abc123def456"
    assert manifest["snapshot_size_bytes"] == 524288000
    assert manifest["qdrant_version"] == "1.12.6"
    assert manifest["engine_commit"] == "7abba11"
    assert manifest["garden_sha"] == "973b326a"
    assert manifest["scoring_sha"] == "e8f1a2b3"
    assert "created" in manifest


def test_list_snapshots_empty(tmp_path, monkeypatch):
    monkeypatch.setattr("benchmark.create_snapshot.SNAPSHOT_DIR", tmp_path)
    assert list_snapshots() == []


def test_list_snapshots_reads_manifests(tmp_path, monkeypatch):
    monkeypatch.setattr("benchmark.create_snapshot.SNAPSHOT_DIR", tmp_path)
    snap_dir = tmp_path / "v2-baseline"
    snap_dir.mkdir()
    manifest = {
        "name": "v2-baseline",
        "point_count": 2400,
        "created": "2026-07-29T10:30:00Z",
        "engine_commit": "7abba11",
        "snapshot_size_bytes": 524288000,
    }
    (snap_dir / "manifest.json").write_text(json.dumps(manifest))
    result = list_snapshots()
    assert len(result) == 1
    assert result[0]["name"] == "v2-baseline"
    assert result[0]["point_count"] == 2400


def test_list_snapshots_skips_dirs_without_manifest(tmp_path, monkeypatch):
    monkeypatch.setattr("benchmark.create_snapshot.SNAPSHOT_DIR", tmp_path)
    (tmp_path / "broken-snap").mkdir()
    assert list_snapshots() == []


def test_compute_sha256(tmp_path):
    test_file = tmp_path / "test.bin"
    test_file.write_bytes(b"hello world")
    sha = _compute_sha256(test_file)
    expected = hashlib.sha256(b"hello world").hexdigest()
    assert sha == expected


def test_create_snapshot_rejects_existing_name(tmp_path, monkeypatch):
    monkeypatch.setattr("benchmark.create_snapshot.SNAPSHOT_DIR", tmp_path)
    existing = tmp_path / "existing"
    existing.mkdir()
    (existing / "manifest.json").write_text("{}")
    from benchmark.create_snapshot import create_snapshot
    with pytest.raises(SystemExit):
        create_snapshot("existing", "http://localhost:8080", "http://localhost:6333")


def test_list_snapshots_sorted_by_name(tmp_path, monkeypatch):
    monkeypatch.setattr("benchmark.create_snapshot.SNAPSHOT_DIR", tmp_path)
    for name in ["beta", "alpha", "gamma"]:
        d = tmp_path / name
        d.mkdir()
        (d / "manifest.json").write_text(json.dumps({"name": name, "point_count": 100}))
    result = list_snapshots()
    assert [s["name"] for s in result] == ["alpha", "beta", "gamma"]


def _create_fake_snapshots(base_dir, names, days_ago):
    for name, ago in zip(names, days_ago):
        d = base_dir / name
        d.mkdir()
        created = datetime.now(timezone.utc) - timedelta(days=ago)
        manifest = {"name": name, "created": created.isoformat(),
                    "point_count": 100, "snapshot_size_bytes": 1024}
        (d / "manifest.json").write_text(json.dumps(manifest))


def test_prune_keeps_n_most_recent(tmp_path, monkeypatch):
    monkeypatch.setattr("benchmark.create_snapshot.SNAPSHOT_DIR", tmp_path)
    _create_fake_snapshots(tmp_path, ["old", "mid", "new"], days_ago=[60, 30, 1])

    pruned = prune_snapshots(keep=2, max_age_days=45, dry_run=False)

    assert len(pruned) == 1
    assert pruned[0]["name"] == "old"
    assert not (tmp_path / "old").exists()
    assert (tmp_path / "mid").exists()
    assert (tmp_path / "new").exists()


def test_prune_respects_max_age(tmp_path, monkeypatch):
    monkeypatch.setattr("benchmark.create_snapshot.SNAPSHOT_DIR", tmp_path)
    _create_fake_snapshots(tmp_path, ["ancient", "recent"], days_ago=[90, 5])

    pruned = prune_snapshots(keep=1, max_age_days=30, dry_run=False)

    assert len(pruned) == 1
    assert pruned[0]["name"] == "ancient"


def test_prune_keep_protects_from_age_deletion(tmp_path, monkeypatch):
    monkeypatch.setattr("benchmark.create_snapshot.SNAPSHOT_DIR", tmp_path)
    _create_fake_snapshots(tmp_path, ["only_one"], days_ago=[90])

    pruned = prune_snapshots(keep=1, max_age_days=30, dry_run=False)

    assert len(pruned) == 0
    assert (tmp_path / "only_one").exists()


def test_prune_dry_run_does_not_delete(tmp_path, monkeypatch):
    monkeypatch.setattr("benchmark.create_snapshot.SNAPSHOT_DIR", tmp_path)
    _create_fake_snapshots(tmp_path, ["old", "new"], days_ago=[60, 1])

    pruned = prune_snapshots(keep=1, max_age_days=30, dry_run=True)

    assert len(pruned) == 1
    assert (tmp_path / "old").exists()


def test_prune_warns_on_orphan_directory(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr("benchmark.create_snapshot.SNAPSHOT_DIR", tmp_path)
    _create_fake_snapshots(tmp_path, ["good"], days_ago=[1])
    (tmp_path / "orphan").mkdir()

    prune_snapshots(keep=1, max_age_days=365, dry_run=False)

    assert "orphan" in capsys.readouterr().err


def test_prune_no_snapshots(tmp_path, monkeypatch):
    monkeypatch.setattr("benchmark.create_snapshot.SNAPSHOT_DIR", tmp_path)

    pruned = prune_snapshots(keep=3, max_age_days=30, dry_run=False)

    assert len(pruned) == 0
