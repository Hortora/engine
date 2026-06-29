#!/usr/bin/env python3
"""SPLADE vocabulary analysis — decode sparse vectors to diagnose domain coverage."""

import json
import sys
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from benchmark.queries import SCENARIOS

MODEL_DIR = Path.home() / ".hortora" / "models" / "splade"
RESULTS_DIR = Path(__file__).parent / "results"
THRESHOLD = 0.01

TIER_1_TERMS = frozenset({
    "applicationscoped", "qualifier", "interceptor", "produces", "singleton",
    "persist", "cascade",
    "panache", "devservices", "jandex", "arc",
    "synchronized", "volatile",
    "mutiny",
})

TIER_2_TERMS = frozenset({
    "inject", "alternative", "scope", "observer",
    "entity", "merge", "lazy", "fetch",
    "atomic", "concurrent", "lock",
    "uni", "multi", "subscribe", "emit",
})


@dataclass
class InputTokens:
    whole: set[str] = field(default_factory=set)
    subwords: set[str] = field(default_factory=set)


def classify_token(token: str, input_tokens: InputTokens) -> tuple[str, str]:
    is_subword = token.startswith("##")
    form = "SUBWORD" if is_subword else "WHOLE_WORD"
    if is_subword:
        source = "INPUT" if token in input_tokens.subwords else "EXPANSION"
    else:
        source = "INPUT" if token in input_tokens.whole else "EXPANSION"
    return source, form


def domain_tier(token: str) -> int:
    clean = token.lstrip("#").lower()
    if clean in TIER_1_TERMS:
        return 1
    if clean in TIER_2_TERMS:
        return 2
    return 0


def get_input_tokens(tokenizer, text: str) -> InputTokens:
    encoding = tokenizer.encode(text)
    tokens = encoding.tokens
    whole = set()
    subwords = set()
    for t in tokens:
        if t in ("[CLS]", "[SEP]", "[PAD]"):
            continue
        if t.startswith("##"):
            subwords.add(t)
        else:
            whole.add(t)
    return InputTokens(whole=whole, subwords=subwords)


def compute_sparse_vector(session, tokenizer, text: str) -> list[tuple[str, float, str, str, int]]:
    encoding = tokenizer.encode(text)
    input_ids = np.array([encoding.ids], dtype=np.int64)
    attention_mask = np.array([encoding.attention_mask], dtype=np.int64)
    token_type_ids = np.zeros_like(input_ids)

    outputs = session.run(None, {
        "input_ids": input_ids,
        "input_mask": attention_mask,
        "segment_ids": token_type_ids,
    })
    raw = outputs[0]
    if raw.ndim == 3:
        logits = raw[0].max(axis=0)
    else:
        logits = raw[0]

    weights = np.log1p(np.maximum(0, logits))
    input_tokens = get_input_tokens(tokenizer, text)
    vocab = tokenizer.get_vocab()
    id_to_token = {v: k for k, v in vocab.items()}

    activated = []
    for idx in np.where(weights >= THRESHOLD)[0]:
        token = id_to_token.get(int(idx), f"[{idx}]")
        weight = float(weights[idx])
        source, form = classify_token(token, input_tokens)
        tier = domain_tier(token)
        activated.append((token, weight, source, form, tier))

    activated.sort(key=lambda x: x[1], reverse=True)
    return activated


def analyze_all(model_path: Path, tokenizer_path: Path) -> list[dict]:
    import onnxruntime as ort
    from tokenizers import Tokenizer

    session = ort.InferenceSession(str(model_path))
    tokenizer = Tokenizer.from_file(str(tokenizer_path))

    output_dim = session.get_outputs()[0].shape[-1]
    print(f"SPLADE model output dimension: {output_dim}")

    results = []
    for scenario in SCENARIOS:
        for qt, query_text in [("KW", scenario.kw_query), ("NL", scenario.nl_query)]:
            print(f"  {scenario.id}/{qt}: ", end="", flush=True)
            activated = compute_sparse_vector(session, tokenizer, query_text)
            input_tokens = get_input_tokens(tokenizer, query_text)

            tier1_hits = [t for t in activated if t[4] == 1]
            tier2_hits = [t for t in activated if t[4] == 2]
            expansions = [t for t in activated if t[2] == "EXPANSION"]

            result = {
                "scenario_id": scenario.id,
                "query_type": qt,
                "query_text": query_text,
                "failure_modes": scenario.failure_modes,
                "input_tokens": {
                    "whole": sorted(input_tokens.whole),
                    "subwords": sorted(input_tokens.subwords),
                },
                "total_activated": len(activated),
                "top_20": [
                    {"token": t[0], "weight": round(t[1], 4), "source": t[2],
                     "form": t[3], "domain_tier": t[4]}
                    for t in activated[:20]
                ],
                "expansion_count": len(expansions),
                "tier1_hits": len(tier1_hits),
                "tier1_tokens": [t[0] for t in tier1_hits],
                "tier2_hits": len(tier2_hits),
                "tier2_tokens": [t[0] for t in tier2_hits],
                "missing_domain_terms": sorted(
                    (TIER_1_TERMS | TIER_2_TERMS) - {t[0].lstrip("#").lower() for t in activated}
                ),
            }
            results.append(result)
            print(f"{len(activated)} tokens, {len(expansions)} expansions, "
                  f"T1={len(tier1_hits)} T2={len(tier2_hits)}")

    return results


def main():
    model_path = MODEL_DIR / "model.onnx"
    tokenizer_path = MODEL_DIR / "tokenizer.json"

    if not model_path.exists():
        print(f"SPLADE model not found at {model_path}")
        print("Run scripts/download-models.sh first")
        sys.exit(1)

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)

    print("Running SPLADE vocabulary analysis...")
    results = analyze_all(model_path, tokenizer_path)

    output_path = RESULTS_DIR / "splade-vocab.json"
    output_path.write_text(json.dumps(results, indent=2))
    print(f"\nResults written to {output_path}")


if __name__ == "__main__":
    main()
