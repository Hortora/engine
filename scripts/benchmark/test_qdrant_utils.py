import json
from unittest.mock import patch, MagicMock
from benchmark.qdrant_utils import (
    check_qdrant_ready, wait_for_readiness,
    ENGINE_URL, QDRANT_URL, COLLECTION_NAME, MIN_INDEXED_POINTS,
)


def _mock_urlopen_response(data: dict, status: int = 200):
    mock_resp = MagicMock()
    mock_resp.read.return_value = json.dumps(data).encode()
    mock_resp.__enter__ = lambda s: s
    mock_resp.__exit__ = MagicMock(return_value=False)
    mock_resp.status = status
    return mock_resp


class TestCheckQdrantReady:
    @patch("benchmark.qdrant_utils.urllib.request.urlopen")
    def test_returns_point_count(self, mock_urlopen):
        mock_urlopen.return_value = _mock_urlopen_response(
            {"result": {"points_count": 2400}}
        )
        assert check_qdrant_ready() == 2400

    @patch("benchmark.qdrant_utils.urllib.request.urlopen")
    def test_uses_custom_qdrant_url(self, mock_urlopen):
        mock_urlopen.return_value = _mock_urlopen_response(
            {"result": {"points_count": 100}}
        )
        check_qdrant_ready("http://custom:6333")
        call_args = mock_urlopen.call_args
        req = call_args[0][0]
        assert "custom:6333" in req.full_url

    @patch("benchmark.qdrant_utils.urllib.request.urlopen")
    def test_raises_on_connection_error(self, mock_urlopen):
        mock_urlopen.side_effect = ConnectionError("refused")
        try:
            check_qdrant_ready()
            assert False, "Should have raised"
        except ConnectionError:
            pass


class TestWaitForReadiness:
    @patch("benchmark.qdrant_utils.READINESS_POLL_S", 0)
    @patch("benchmark.qdrant_utils.urllib.request.urlopen")
    def test_returns_when_engine_and_qdrant_ready(self, mock_urlopen):
        engine_resp = _mock_urlopen_response([{"id": "test.md", "title": "t"}])
        qdrant_resp_1 = _mock_urlopen_response({"result": {"points_count": 2000}})
        qdrant_resp_2 = _mock_urlopen_response({"result": {"points_count": 2000}})
        qdrant_resp_3 = _mock_urlopen_response({"result": {"points_count": 2000}})
        mock_urlopen.side_effect = [engine_resp, qdrant_resp_1, qdrant_resp_2, qdrant_resp_3]
        count = wait_for_readiness(min_points=1900)
        assert count == 2000

    @patch("benchmark.qdrant_utils.READINESS_POLL_S", 0)
    @patch("benchmark.qdrant_utils.urllib.request.urlopen")
    def test_raises_if_engine_never_responds(self, mock_urlopen):
        mock_urlopen.side_effect = ConnectionError("refused")
        try:
            wait_for_readiness()
            assert False, "Should have raised"
        except RuntimeError as e:
            assert "not responding" in str(e)


def test_constants():
    assert ENGINE_URL == "http://localhost:8080"
    assert QDRANT_URL == "http://localhost:6333"
    assert COLLECTION_NAME == "hortora_garden"
    assert MIN_INDEXED_POINTS == 1900
