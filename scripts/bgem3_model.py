"""BGE-M3 three-head PyTorch wrapper for ONNX export.

Adapted from https://huggingface.co/aapot/bge-m3-onnx/blob/main/bgem3_model.py
with three modifications:
  1. Sparse scatter baked into forward() — produces [batch, vocab_size]
  2. Output names: 'dense', 'sparse', 'colbert' (matching BgeM3Embedder.java)
  3. ColBERT includes CLS token (required for OnnxInferenceModel.runBatch())
"""

import os
import torch
from torch import nn, Tensor
from transformers import AutoModel, AutoConfig
from huggingface_hub import snapshot_download
from typing import Dict


class BGEM3InferenceModel(nn.Module):

    def __init__(self, model_name: str = "BAAI/bge-m3", colbert_dim: int = -1):
        super().__init__()
        model_name = snapshot_download(
            repo_id=model_name,
            allow_patterns=[
                "pytorch_model.bin",
                "colbert_linear.pt",
                "sparse_linear.pt",
                "config.json",
                "tokenizer.json",
                "tokenizer_config.json",
                "sentencepiece.bpe.model",
                "special_tokens_map.json",
            ],
        )
        self.config = AutoConfig.from_pretrained(model_name)
        self.model = AutoModel.from_pretrained(model_name)
        self.colbert_linear = nn.Linear(
            in_features=self.model.config.hidden_size,
            out_features=(
                self.model.config.hidden_size if colbert_dim == -1 else colbert_dim
            ),
        )
        self.sparse_linear = nn.Linear(
            in_features=self.model.config.hidden_size, out_features=1
        )
        colbert_state_dict = torch.load(
            os.path.join(model_name, "colbert_linear.pt"),
            map_location="cpu", weights_only=True,
        )
        sparse_state_dict = torch.load(
            os.path.join(model_name, "sparse_linear.pt"),
            map_location="cpu", weights_only=True,
        )
        self.colbert_linear.load_state_dict(colbert_state_dict)
        self.sparse_linear.load_state_dict(sparse_state_dict)
        self._model_dir = model_name

    @property
    def tokenizer_path(self) -> str:
        return os.path.join(self._model_dir, "tokenizer.json")

    def forward(self, input_ids: Tensor, attention_mask: Tensor) -> Dict[str, Tensor]:
        last_hidden_state = self.model(
            input_ids=input_ids, attention_mask=attention_mask, return_dict=True
        ).last_hidden_state

        # Dense: CLS pooling + L2 normalize
        dense = torch.nn.functional.normalize(last_hidden_state[:, 0], dim=-1)

        # Sparse: per-token weight -> ReLU -> scatter to vocab with max reduction
        token_weights = torch.relu(self.sparse_linear(last_hidden_state)).squeeze(-1)
        sparse = torch.zeros(
            input_ids.shape[0], self.config.vocab_size,
            device=token_weights.device, dtype=token_weights.dtype,
        )
        sparse = torch.scatter_reduce(sparse, 1, input_ids, token_weights, reduce="amax")

        # ColBERT: all tokens (including CLS) -> linear -> mask padding -> L2 normalize
        colbert = self.colbert_linear(last_hidden_state)
        colbert = colbert * attention_mask[:, :, None].float()
        colbert = torch.nn.functional.normalize(colbert, dim=-1)
        colbert = colbert * attention_mask[:, :, None].float()

        return {"dense": dense, "sparse": sparse, "colbert": colbert}
