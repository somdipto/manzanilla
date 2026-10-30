"""Translation-only Gemini OpenAI-compatible streaming and read-only readiness."""
import json
import asyncio
from urllib.parse import quote
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

    async def validate(self):
        """Read-only model visibility, not proof of quota or translation quality.

        https://ai.google.dev/gemini-api/docs/openai#retrieve-a-model
        """
        try:
            async with asyncio.timeout(8):
                async with httpx.AsyncClient(timeout=8, follow_redirects=False) as client:
                    async with client.stream('GET', self._base + '/models/' + quote(self._model, safe=''),
                                             headers={'Authorization': 'Bearer ' + self._key}) as response:
                        response.raise_for_status()
                        body = bytearray()
                        async for chunk in response.aiter_bytes():
                            body.extend(chunk)
                            if len(body) > 16384:
                                raise ValueError
                        value = json.loads(body)
                        if (not isinstance(value, dict)
                                or value.get('id') not in (self._model, 'models/' + self._model)):
                            raise ValueError
        except (httpx.HTTPError, ValueError, TimeoutError):
            raise RuntimeError('Translation model access unavailable') from None

    async def stream(self, system: str, utterance: str) -> AsyncIterator[str]:
        payload = {
            'model': self._model,
            'stream': True,
            'temperature': 0,
            'max_tokens': 4096,
            'messages': [
                {'role': 'system', 'content': system},
                {'role': 'user', 'content': utterance},
            ],
        }
        headers = {'Authorization': 'Bearer ' + self._key}
        completed = False
        received = 0
        try:
            async with asyncio.timeout(self._timeout), httpx.AsyncClient(timeout=self._timeout, follow_redirects=False) as client:
                async with client.stream('POST', self._base + '/chat/completions',
                                         json=payload, headers=headers) as response:
                    response.raise_for_status()
                    async for line in response.aiter_lines():
                        received += len(line)
                        if received > 262144 or len(line) > 32768:
                            raise RuntimeError('Translation stream failed')
                        if not line.startswith('data:'):
                            continue
                        data = line[5:].strip()
                        if data == '[DONE]':
                            if not completed:
                                raise RuntimeError('Translation stream incomplete')
                            return
                        try:
                            chunk = json.loads(data)
                            if not isinstance(chunk, dict) or 'error' in chunk:
                                raise ValueError
                            choices = chunk['choices']
                            # OpenAI-compatible streams can include a final usage
                            # chunk without choices; it is not completion proof.
                            if choices == [] and isinstance(chunk.get('usage'), dict):
                                continue
                            if not isinstance(choices, list) or len(choices) != 1 or completed:
                                raise ValueError
                            choice = choices[0]
                            delta = choice['delta']
                            text = delta.get('content') or ''
                            if (not isinstance(text, str) or delta.get('refusal')
                                    or delta.get('tool_calls') or delta.get('function_call')):
                                raise ValueError
                            reason = choice.get('finish_reason')
                            if reason is not None:
                                if reason != 'stop':
                                    raise ValueError
                                completed = True
                        except (ValueError, KeyError, IndexError, TypeError, AttributeError):
                            raise RuntimeError('Translation stream failed') from None
                        if text:
                            yield text
        except (httpx.HTTPError, TimeoutError):
            raise RuntimeError('Translation stream failed') from None
        raise RuntimeError('Translation stream incomplete')
