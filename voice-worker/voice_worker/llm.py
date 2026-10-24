"""Streaming translation over an OpenAI-compatible chat endpoint.

Production uses Gemini's OpenAI-compatible route; local benches use any
compatible endpoint (for example NVIDIA NIM). The worker never sees provider
credentials other than through its own environment.
"""
import time
from typing import AsyncIterator

import httpx


class TranslateStream:
    def __init__(self, base_url: str, api_key: str, model: str, timeout: float = 30.0):
        if not api_key:
            raise ValueError('An LLM credential is required for translation')
        self._base = base_url.rstrip('/')
        self._key = api_key
        self._model = model
        self._timeout = timeout
        self.first_token_at: float | None = None

    async def stream(self, system: str, utterance: str) -> AsyncIterator[str]:
        payload = {
            'model': self._model,
            'stream': True,
            'temperature': 0.2,
            'messages': [
                {'role': 'system', 'content': system},
                {'role': 'user', 'content': utterance},
            ],
        }
        headers = {'Authorization': 'Bearer ' + self._key}
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            async with client.stream('POST', self._base + '/chat/completions',
                                     json=payload, headers=headers) as response:
                response.raise_for_status()
                async for line in response.aiter_lines():
                    if not line.startswith('data:'):
                        continue
                    data = line[5:].strip()
                    if data == '[DONE]':
                        return
                    try:
                        import json
                        chunk = json.loads(data)
                        delta = chunk['choices'][0]['delta'].get('content') or ''
                    except (ValueError, KeyError, IndexError, TypeError):
                        continue
                    if delta:
                        if self.first_token_at is None:
                            self.first_token_at = time.time()
                        yield delta
