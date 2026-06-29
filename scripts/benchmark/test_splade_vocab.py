# scripts/benchmark/test_splade_vocab.py
from benchmark.splade_vocab import (
    classify_token, TIER_1_TERMS, TIER_2_TERMS, domain_tier, InputTokens,
)

def test_classify_input_whole_word():
    input_tokens = InputTokens(whole={"default", "bean"}, subwords={"##ing"})
    source, form = classify_token("default", input_tokens)
    assert source == "INPUT"
    assert form == "WHOLE_WORD"

def test_classify_input_subword():
    input_tokens = InputTokens(whole={"default"}, subwords={"##tion"})
    source, form = classify_token("##tion", input_tokens)
    assert source == "INPUT"
    assert form == "SUBWORD"

def test_classify_expansion_whole_word():
    input_tokens = InputTokens(whole={"default"}, subwords=set())
    source, form = classify_token("bean", input_tokens)
    assert source == "EXPANSION"
    assert form == "WHOLE_WORD"

def test_classify_expansion_subword():
    input_tokens = InputTokens(whole={"default"}, subwords=set())
    source, form = classify_token("##ject", input_tokens)
    assert source == "EXPANSION"
    assert form == "SUBWORD"

def test_tier_1_terms_are_unambiguous():
    for term in TIER_1_TERMS:
        assert domain_tier(term) == 1, f"{term} should be Tier 1"

def test_tier_2_terms_are_ambiguous():
    for term in TIER_2_TERMS:
        assert domain_tier(term) == 2, f"{term} should be Tier 2"

def test_non_domain_term():
    assert domain_tier("the") == 0
    assert domain_tier("computer") == 0
